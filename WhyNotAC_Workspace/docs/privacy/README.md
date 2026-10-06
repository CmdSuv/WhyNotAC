# Kişisel veri talepleri / Data-subject requests

WhyNotAC recordings (`plugins/WhyNotAC/recordings/<date>/*.wnrec`) are pseudonymised. They contain
no IP address, name, chat or commands. The player UUID is replaced by
`HMAC-SHA256(recording-secret.key, uuid)`, so the server operator, and nobody else, can find one
player's recordings. The plugin jar is also the tool for this.

| Talep / Request | Komut / Command |
|---|---|
| Hangi kayıtlar var? / Which recordings exist? | `java -jar WhyNotAC.jar find <recordings-dir> <secret-key> <uuid>` |
| Kopya / Copy (access, portability) | `java -jar WhyNotAC.jar dump <file.wnrec> > copy.jsonl` for each file listed by `find` |
| Silme / Erasure | `java -jar WhyNotAC.jar forget <recordings-dir> <secret-key> <uuid>` (dry run), then the same command with `--yes` |

`<recordings-dir>` is `plugins/WhyNotAC/recordings` and `<secret-key>` is
`plugins/WhyNotAC/recording-secret.key`. The output is JSON. It lists every matching file with its
size, start time (UTC), and whether the session had finished.

## Finding the UUID

- **Online-mode server:** the player's Mojang UUID. Look it up in `usercache.json` in the server
  directory, or ask the player for it.
- **Offline-mode server (or a proxy without UUID forwarding):** the UUID the server assigned. Look
  it up in `usercache.json`. Do not use the Mojang UUID here.

Verify that the person making the request really owns the account (for example, have them run a
command in game) before you hand out a copy.

## Rules

- **Run `forget` while the player is offline.** A session that is still running keeps writing its
  `.wnrec.part` file. Kick the player first, or wait until they have left.
- **Exit code 2** means some files could not be read, for example a crash left a damaged header.
  They are listed under `unreadable_for_manual_review`. Their body can still hold the pseudonym,
  so check them with `inspect`/`dump`, or delete them.
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
