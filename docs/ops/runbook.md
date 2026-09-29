# SecureLeaf — Operations runbook (one Oracle server)

> Phase 09D D13. Setup and first deploy are in [`../deployment.md`](../deployment.md). This page is for
> **day 2**: something is wrong, or something must change. Everything runs from `/opt/secureleaf` as the
> `ubuntu` user. Handy prefix: `cd /opt/secureleaf/infra/prod && alias dc='docker compose --env-file .env'`.

## Where things are

| Question | Command |
|---|---|
| Is it up? | `curl -s https://<SITE_HOST>/actuator/health` · `dc ps` |
| Logs | `dc logs -f --tail 200 backend` (JSON, one object per line; grep a `correlationId`) · `caddy`, `postgres` likewise |
| Resource use | `docker stats --no-stream` · `free -h` · `df -h /` |
| Last deploys | `docker image ls secureleaf/backend` (tags are git SHAs) · `cat infra/prod/.last-good-tag` |
| Backup timer | `systemctl list-timers secureleaf-backup.timer` · `journalctl -u secureleaf-backup.service -e` |
| Processing time | backend log line `Pipeline complete … in N ms`; `processing_jobs.started_at`/`completed_at` |
| Mail failures | metric `secureleaf.mail.send.failures` (`dc exec backend curl -s localhost:8080/actuator/metrics/secureleaf.mail.send.failures`) |

## Restart

```bash
dc restart backend            # one service
dc up -d                      # apply changes / start anything that stopped
dc down && dc up -d           # everything (data is in named volumes and survives)
```
Containers use `restart: unless-stopped`, so they also come back after a reboot. **Never `dc down -v`**
on production: `-v` deletes the database and object volumes.

## Bad deploy

`deploy.sh` rolls back automatically and exits non-zero. If you must do it by hand:
`IMAGE_TAG=<previous-sha> dc up -d --no-build` (list tags with `docker image ls secureleaf/backend`).
A rollback restores *code*; a Flyway migration that already ran stays applied — restore from a backup only
if the migration was destructive.

## Disk full

1. `df -h /` and `docker system df` to see who is big.
2. `docker image prune -af` (old images), `docker builder prune -af` (build cache) — safe.
3. Logs are already rotated (10 MB × 3 per container). `journalctl --vacuum-size=200M`.
4. `/var/backups/secureleaf` keeps only the newest dump. Object data grows in the `miniodata` volume; if it is
   the culprit, the boot volume can be enlarged in the Oracle console (up to 200 GB free), then
   `sudo growpart /dev/sda 1 && sudo resize2fs /dev/sda1` (check device names with `lsblk`).
5. Postgres refuses writes when its volume is full — after freeing space, `dc restart postgres backend`.

## Rotate a secret

Edit `infra/prod/.env`, then `dc up -d` (containers whose environment changed are recreated). What breaks:

| Secret | Effect while rotating | Notes |
|---|---|---|
| `JWT_SECRET` | every user is signed out | do it in quiet hours; no dual-key window exists |
| `DRM_SIGNING_SECRET` | signed tile URLs (30 s TTL) stop validating; in-flight page loads retry | harmless |
| `RAZORPAY_KEY_SECRET` / `WEBHOOK_SECRET` | rotate in the Razorpay dashboard **first**, then here; checkouts/webhooks fail verification in between (safe: rejected, and reconciliation recovers them) | |
| `MINIO_ROOT_USER/PASSWORD` | **must be changed inside MinIO too**: `dc exec minio …`; simplest is set new values, `dc up -d` (MinIO reads root creds at start; the volume keeps the data) | verify uploads afterwards |
| `DB_PASSWORD` | Postgres only reads it on first init — change it *in the database first*: `dc exec postgres psql -U secureleaf_user -d secureleaf -c "ALTER USER secureleaf_user PASSWORD '…'"`, then update `.env`, `dc up -d backend` | otherwise the backend cannot log in |
| `REDIS_PASSWORD` | `dc up -d redis backend`; active viewer sessions/tickets are lost (Redis AOF keeps data, but clients reconnect) | |
| `MAIL_PASS` | new SMTP key in Brevo → `.env` → `dc up -d backend` | |

If a secret **leaked**: rotate it immediately, and for `JWT_SECRET` this also invalidates every stolen token.

## Restore drill

Do this on a *scratch* server (or locally with the test override) — never over production.

1. New VM → [`deployment.md`](../deployment.md) §1–§4 (setup script, then `generate-env.sh`; or copy your saved `.env`).
   Use the **same** `OCI_S3_*` values.
2. `infra/prod/scripts/deploy.sh` (brings the stack up empty), then `infra/prod/scripts/restore.sh`
   (newest dump, or `restore.sh secureleaf-2026-09-01.dump`). It restores Postgres and the buckets, then starts the stack.
3. Check: log in as a known user, open a purchased document, `select count(*) from users`.
4. Time it and write the time down. `infra/prod/test/backup-restore-test.sh` automates the same round trip locally.

## Renew or recover TLS

Caddy renews certificates by itself ~30 days before expiry, storing them in the `caddy_data` volume.
If HTTPS breaks: `dc logs caddy | grep -i -E "error|obtain"`. The usual causes:
- **Port 80/443 blocked** — check *both* firewalls (VCN security list **and** host iptables: `sudo iptables -L INPUT -n --line-numbers`; ACCEPT for 80/443 must sit *above* the REJECT).
- **DuckDNS points to the wrong IP** (e.g. after a rebuild) — update it at duckdns.org.
- **Let's Encrypt rate limit** (5 duplicate certs/week) — wait, do not delete `caddy_data` repeatedly.
- Force a retry: `dc restart caddy`.

## Oracle stopped or reclaimed the VM

Oracle can stop instances it judges idle, or (worst case) suspend the account. Order of response:
1. Console → Instances → **Start**. Ensure the public IP is unchanged (reserved IPs survive); otherwise update DuckDNS. `dc ps` — services come back by themselves.
2. If the instance is gone: create a new one (same shape) and **rebuild on a new VM in about an hour**:

| Step | Time |
|---|---|
| Create VM, open VCN 80/443, point DuckDNS at it | 10 min |
| `git clone`, `setup-server.sh` | 10 min |
| Put back your saved `.env` (or `generate-env.sh` and re-enter externals) | 5 min |
| `deploy.sh` (image builds) | 10–15 min |
| `restore.sh` (DB + buckets from Object Storage) | 5–15 min, size dependent |
| Post-deploy checks | 10 min |

3. If **Oracle itself is unreachable / the account is terminated**, Object Storage goes with it. Use the copy you
   keep off Oracle: `download-backup.sh` dumps on your laptop. Restore on any Ubuntu/ARM (or amd64) VPS with
   `restore.sh` after placing the dump in Object Storage-compatible storage, or restore the dump directly:
   `dc exec -T postgres pg_restore --clean --if-exists --no-owner -U secureleaf_user -d secureleaf < secureleaf-….dump`
   (uploaded documents/tiles need a bucket copy; tiles can be regenerated from raw PDFs, raw PDFs cannot).
4. To avoid idle reclaim: steady memory use is above Oracle's 20% threshold (see ADR 0001); if you ever run the
   stack lighter, add a small periodic load or check the instance's metrics.

## Common symptoms

| Symptom | Likely cause → action |
|---|---|
| Site unreachable, `curl` times out | VCN security list or host iptables (two-firewall trap) |
| Browser TLS error | Caddy could not get a cert — see *Renew or recover TLS* |
| `502` from Caddy | backend restarting/OOM: `dc logs backend`, `docker inspect -f '{{.State.OOMKilled}}' secureleaf-backend-1` |
| Backend exits at boot naming a variable | `ProdSecretsConfig`: that secret is empty/short/default — fix `.env` |
| Uploads stuck "processing" | one at a time by design (`processing.thread-pool` = 1); check `dc logs backend | grep job-` |
| Watermark blank / errors only in prod | fonts missing in the image: `infra/prod/scripts/smoke-watermark.sh` |
| No emails | Brevo sender not verified (`MAIL_FROM`), daily cap (~300), or wrong SMTP key: metric above + `dc logs backend | grep -i "email failed"` |
