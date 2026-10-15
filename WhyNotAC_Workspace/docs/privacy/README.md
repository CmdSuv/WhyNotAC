# Kişisel veri talepleri / Data-subject requests

WhyNotAC recordings (`plugins/WhyNotAC/recordings/<date>/*.wnrec`) are pseudonymised. They contain
no IP address, name, chat or commands. Every UUID in a recording is replaced by
`HMAC-SHA256(recording-secret.key, uuid)`: the recorded player's, and those of the other players in its
player list and of the entities it was sent. So the server operator, and nobody else, can find one
player's recordings. The plugin jar is also the tool for this.

A recording is about one player (its header names that player's pseudonym, which `find` and `forget`
match). Other players appear in it only by pseudonym, as entries of the recorded player's player list
and as entities near them. `find` lists those recordings too, under `mentioned_in`, with the number of
occurrences. `forget --yes` deletes the player's own recordings and rewrites each finished recording
that mentions them: their pseudonym is replaced by a fresh random UUID, the same one throughout that
file, so the recording still replays exactly as before but no longer links to the player. The
rewritten file is read back and checked before it replaces the original.

## What WhyNotAC stores

| Where | What | Who it names | Kept |
|---|---|---|---|
| `plugins/WhyNotAC/recordings/<date>/*.wnrec` | Session recordings (only with `recording.enabled`) | Pseudonyms only | `recording.max-age-days` (14) |
| `plugins/WhyNotAC/cases.db`, `plugins/WhyNotAC/cases/<id>.json` | Cases: one per finding of a check, for staff review. A dossier names the recording that holds the finding | The player's name and UUID | `cases.retention-days` (90) |
| The server log (`logs/`) | Staff alert lines (player name, check, finding, case id). Warnings name a connection number and the player's name, never an IP address | The player's name | Your log retention (see below) |

Retention is enforced at startup and then every hour.

## Requests

Run them on the **server console** while the server is running. Each one covers everything above
except the server log: the player's recordings, the mentions in other players' recordings, the case
rows and the dossiers. Players cannot run these commands, not even operators.

| Talep / Request | Komut / Command |
|---|---|
| Ne var? / What is stored? | `whynotac privacy find <uuid>` |
| Kopya / Copy (access, portability) | `java -jar WhyNotAC.jar dump <file.wnrec> > copy.jsonl` for each recording listed by `find`; the dossiers listed by `find` are JSON already |
| Silme / Erasure | `whynotac privacy forget <uuid>` (dry run), then `whynotac privacy forget <uuid> --yes` |

The `recordings:` line is JSON. It lists every matching recording with its size, start time (UTC),
and whether the session had finished, then the other players' recordings that mention the player.
The `cases:` line lists the case ids and dossier files.

The jar also works without the server, for recordings only (the cases database needs the server's
SQLite driver): `java -jar WhyNotAC.jar find|forget <recordings-dir> <secret-key> <uuid> [--yes]`,
where `<recordings-dir>` is `plugins/WhyNotAC/recordings` and `<secret-key>` is
`plugins/WhyNotAC/recording-secret.key`.

## Finding the UUID

- **Online-mode server:** the player's Mojang UUID. Look it up in `usercache.json` in the server
  directory, or ask the player for it.
- **Offline-mode server (or a proxy without UUID forwarding):** the UUID the server assigned. Look
  it up in `usercache.json`. Do not use the Mojang UUID here.

Verify that the person making the request really owns the account (for example, have them run a
command in game) before you hand out a copy.

## Rules

- **Run `forget` while the player is offline.** A session that is still running keeps writing its
  `.wnrec.part` file, so the console command refuses while the player is online. Kick the player
  first, or wait until they have left. Another player's session
  that is still running can mention them too: `forget --yes` cannot rewrite a `.wnrec.part` file and
  lists it under `unreadable_for_manual_review`. Run `forget --yes` again once that session has ended.
- **Incomplete results.** Some files could not be read, for example a crash left a damaged header
  (the jar exits with code 2; the console says "incomplete"). They are listed under
  `unreadable_for_manual_review` or "for manual review". Their body can still hold the pseudonym,
  so check them with `inspect`/`dump`, or delete them.
- **The server log** is not covered: alert lines name players. Paper keeps old logs in `logs/`
  without a limit. Delete old logs on a schedule that matches your privacy notice, or search them
  for the player's name when you handle a request.
- **Backups** are outside the tool's reach. Delete the same files from your backups, or document
  that backups expire within the retention period.
- **Retention** is enforced automatically (`recording.max-age-days`, default 14). Keep the
  privacy notice in line with it.
- **Rotating the secret.** Deleting or replacing `recording-secret.key` permanently unlinks every
  existing recording from every player, including from `find`. Only do this when you also delete
  the recordings, or deliberately want to anonymise them.
- **Back up the secret** separately from the recordings and encrypted (for example, in your
  password manager). Without it, `find`/`forget` can no longer match anyone, and erasure requests
  can then only be met by deleting every recording.
- **Keep the secret private.** Anyone with the key and a UUID can confirm which recordings belong
  to that player. It never needs to leave the server.

The player-facing notice template is in [privacy-notice-template.md](privacy-notice-template.md).
