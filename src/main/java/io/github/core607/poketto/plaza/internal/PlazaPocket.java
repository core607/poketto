package io.github.core607.poketto.plaza.internal;

import io.github.core607.poketto.plaza.PlazaException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Callers hold the account guard; notes and discoveries never grant content authority. */
final class PlazaPocket {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    PlazaPocket(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    List<Note> notes(UUID account) {
        return jdbc.query(
                "select note_id,body,client_name,created_at from plaza_notes where account_id=? and not deleted "
                        + "order by created_at desc,note_id limit 20",
                (row, number) -> new Note(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        row.getString(3),
                        row.getTimestamp(4).toInstant()),
                account);
    }

    Note write(UUID account, UUID request, String body, String clientName) {
        String text = body.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > 1000) {
            throw new PlazaException("INVALID_NOTE", "A note needs 1–1000 characters.", "--help");
        }
        refuseRemoved(account, request);
        List<Note> existing = jdbc.query(
                "select note_id,body,client_name,created_at from plaza_notes " + "where account_id=? and request_id=?",
                (row, number) -> new Note(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        row.getString(3),
                        row.getTimestamp(4).toInstant()),
                account,
                request);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().body().equals(text)) {
                throw new PlazaException("REQUEST_CONFLICT", "This request already wrote a different note.", "pocket");
            }
            return existing.getFirst();
        }
        if (notes(account).size() >= 20) {
            throw new PlazaException(
                    "POCKET_FULL", "Twenty notes fill the pocket; remove one before adding another.", "pocket");
        }
        if (jdbc.queryForObject("select count(*) from plaza_notes where account_id=?", Long.class, account) >= 1000) {
            throw new PlazaException(
                    "NOTE_CAPACITY", "The account's retained note receipt limit has been reached.", "pocket");
        }
        var note = new Note(UUID.randomUUID(), text, clientName, clock.instant());
        jdbc.update(
                "insert into plaza_notes(note_id,account_id,request_id,body,client_name,created_at) values (?,?,?,?,?,?)",
                note.id(),
                account,
                request,
                note.body(),
                note.clientName(),
                Timestamp.from(note.createdAt()));
        return note;
    }

    void remove(UUID account, UUID id) {
        jdbc.update(
                "update plaza_notes set body='',client_name='',deleted=true where account_id=? and note_id=?",
                account,
                id);
    }

    private void refuseRemoved(UUID account, UUID request) {
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from plaza_notes where account_id=? and request_id=? and deleted)",
                Boolean.class,
                account,
                request))) {
            throw new PlazaException(
                    "NOTE_REMOVED", "This request wrote a removed note; it will not be recreated.", "pocket");
        }
    }

    Set<String> discovered(UUID account) {
        return Set.copyOf(
                jdbc.queryForList("select tag from plaza_discoveries where account_id=?", String.class, account));
    }

    void discover(UUID account, List<String> tags) {
        for (String tag : tags) {
            jdbc.update(
                    "insert into plaza_discoveries(account_id,tag,discovered_at) values (?,?,?) on conflict do nothing",
                    account,
                    tag,
                    Timestamp.from(clock.instant()));
        }
        jdbc.update(
                "delete from plaza_discoveries where account_id=? and tag in "
                        + "(select tag from plaza_discoveries where account_id=? order by discovered_at desc,tag offset 512)",
                account,
                account);
    }

    long visitors(String tag) {
        return jdbc.queryForObject("select count(*) from plaza_discoveries where tag=?", Long.class, tag);
    }

    record Note(UUID id, String body, String clientName, Instant createdAt) {}
}
