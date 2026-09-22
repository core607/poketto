package io.github.core607.poketto.web.internal;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.assets.MediaPlayback;
import io.github.core607.poketto.workspace.Workspace;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MediaFileControllerTests {
    @Test
    void playbackUsesExactRangeHeadersAndKeepsOrdinaryDownloadsSeparate() throws Exception {
        var workspace = WorkspaceId.random();
        var media = mock(MediaFileService.class);
        var download = mock(MediaFileService.Download.class);
        byte[] bytes = "RIFF1234WAVE audio fixture".getBytes(StandardCharsets.US_ASCII);
        when(download.filename()).thenReturn("sound.wav");
        when(download.size()).thenReturn((long) bytes.length);
        when(download.playback()).thenReturn(MediaPlayback.WAV);
        when(media.publicDownload(eq(workspace), anyString(), eq("/note"), eq("public/sound.wav")))
                .thenReturn(download);
        doAnswer(call -> {
                    ((OutputStream) call.getArgument(0))
                            .write(
                                    bytes,
                                    ((Long) call.getArgument(1)).intValue(),
                                    ((Long) call.getArgument(2)).intValue());
                    return null;
                })
                .when(download)
                .playTo(any(), anyLong(), anyLong());
        var mvc = MockMvcBuilders.standaloneSetup(new MediaFileController(media))
                .setControllerAdvice(new ProblemResponses())
                .build();
        var request = get("/api/public/media")
                .param("workspace", workspace.toString())
                .param("commit", "a".repeat(40))
                .param("route", "/note")
                .param("path", "public/sound.wav")
                .param("play", "true");
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(content().contentType("audio/wav"))
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("inline;")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(request.header("Range", "bytes=4-7"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 4-7/" + bytes.length))
                .andExpect(header().longValue("Content-Length", 4))
                .andExpect(content().bytes(Arrays.copyOfRange(bytes, 4, 8)));
        mvc.perform(request.header("If-Range", "unissued-validator"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
        doThrow(new IllegalArgumentException("invalid container"))
                .when(download)
                .playTo(any(), anyLong(), anyLong());
        mvc.perform(request).andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void activeOriginalsAreAttachmentsAndPreOutputFailuresDoNotCommitDownloadHeaders() throws Exception {
        var workspace = new Workspace(WorkspaceId.random(), "Synthetic");
        var media = mock(MediaFileService.class);
        var download = mock(MediaFileService.Download.class);
        byte[] bytes = "<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8);
        when(download.filename()).thenReturn("原件.svg");
        when(download.size()).thenReturn((long) bytes.length);
        when(media.publicDownload(eq(workspace.id()), anyString(), eq("/note"), eq("public/原件.svg")))
                .thenReturn(download);
        doAnswer(call -> {
                    ((OutputStream) call.getArgument(0)).write(bytes);
                    return null;
                })
                .when(download)
                .writeTo(any());
        var mvc = MockMvcBuilders.standaloneSetup(new MediaFileController(media))
                .setControllerAdvice(new ProblemResponses())
                .build();
        var request = get("/api/public/media")
                .param("workspace", workspace.id().toString())
                .param("commit", "a".repeat(40))
                .param("route", "/note")
                .param("path", "public/原件.svg");
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment;")))
                .andExpect(header().string("Content-Disposition", Matchers.containsString("filename*=UTF-8''")));
        doThrow(new AssetStorageException(AssetStorageException.Reason.UNAVAILABLE))
                .when(download)
                .writeTo(any());
        mvc.perform(request)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
