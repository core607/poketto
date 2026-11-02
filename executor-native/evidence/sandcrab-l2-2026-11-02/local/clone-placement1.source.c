#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* Linux clone3 UAPI v2: include/uapi/linux/sched.h, 11 aligned u64 fields. */
#define CLONE_INTO_CGROUP (1ULL << 33)
struct clone_args {
    uint64_t flags, pidfd, child_tid, parent_tid, exit_signal;
    uint64_t stack, stack_size, tls, set_tid, set_tid_size, cgroup;
};

static uint64_t now(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value)) _exit(90);
    return (uint64_t)value.tv_sec * 1000000000ULL + value.tv_nsec;
}

int main(int argc, char **argv) {
    if (argc != 3 || (strcmp(argv[1], "move") && strcmp(argv[1], "clone"))) return 2;
    int group = open(argv[2], O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (group < 0) return 3;
    int direct = !strcmp(argv[1], "clone");
    for (int sample = 0; sample < 50; sample++) {
        uint64_t start = now();
        pid_t pid;
        if (direct) {
            struct clone_args args = {0};
            args.flags = CLONE_INTO_CGROUP;
            args.exit_signal = SIGCHLD;
            args.cgroup = group;
            pid = syscall(SYS_clone3, &args, sizeof(args));
        } else {
            pid = fork();
        }
        if (pid < 0) {
            printf("{\"mode\":\"%s\",\"sample\":%d,\"error\":%d}\n", argv[1], sample, errno);
            return 4;
        }
        if (pid == 0) {
            if (!direct) {
                int procs = openat(group, "cgroup.procs", O_WRONLY | O_NOFOLLOW | O_CLOEXEC);
                if (procs < 0 || write(procs, "0", 1) != 1) _exit(91);
            }
            _exit(0);
        }
        int status;
        while (waitpid(pid, &status, 0) < 0) {
            if (errno != EINTR) return 5;
        }
        if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) return 6;
        printf("{\"mode\":\"%s\",\"sample\":%d,\"elapsedNs\":%" PRIu64 "}\n", argv[1], sample, now() - start);
    }
    return 0;
}
