# VM Provisioning Runbook

Builds the BunnyCal production host from a stock image. Follow top to bottom;
each step assumes the previous one succeeded.

**Target:** one Hetzner **CX43** (8 vCPU / 16 GB / 160 GB) in **hel1 (Helsinki)**,
**Ubuntu 24.04 LTS**.

> Use the plain Ubuntu image, **not** Hetzner's Docker app image. Postgres runs
> on the host here, so this is not a pure Docker host, and provisioning Docker
> ourselves keeps this runbook valid from a stock image — which is what makes it
> usable for a rebuild or a move to another provider.

Estimated time: about an hour, most of it waiting on `apt`.

---

## Before you start

Have these ready. Steps will block without them.

| Item | Where it comes from |
|---|---|
| S3 bucket in **`eu-north-1`** + a dedicated IAM key scoped to it | AWS — a different vendor than Hetzner, and in the EU (see GDPR note below) |
| `CALENDAR_TOKEN_ENCRYPTION_KEY_BASE64` | Password manager. **The existing value** — a new one orphans every calendar connection |
| All other `.env` secrets | Password manager / existing `.env.prod` |
| DNS control for `api.bunnycal.io` | Registrar |

> **Why `eu-north-1`:** the VM is in Helsinki and the backups contain every
> user's PII plus their encrypted OAuth tokens. Keeping them in the EU avoids an
> international personal-data transfer under GDPR, and Stockholm is the closest
> AWS region so restores run fast.
>
> **Use a dedicated IAM user scoped to this bucket only** — do not reuse the
> frontend deploy credentials. That key can write to the site buckets, and the
> database host should not be able to deface the website.

Two new secrets get generated in this runbook. **Store both in a password
manager as you create them:**
- the Postgres `bunnycal` role password
- `repo1-cipher-pass` — **unrecoverable after `stanza-create`**

---

## 1. Create the server

Hetzner Cloud console → **CX43**, location **Helsinki (hel1)**, image
**Ubuntu 24.04**, your SSH key.

> The CX tier is **EU-only**. It is not available in Ashburn or Singapore, where
> the more expensive CPX/CCX tiers are the alternative. This is the main reason
> the deployment lives in the EU.

Attach a **cloud firewall** with inbound rules:

| Port | Source | Why |
|---|---|---|
| 22 | your IP if it is static, else anywhere | SSH |
| 80 | anywhere | ACME HTTP-01 challenge — **required**, not just a redirect |
| 443 | anywhere | the API |

Nothing else. Postgres binds to `localhost` and Redis publishes no ports, so
they are unreachable off-box by construction — the firewall is the second layer.

Point the `api.bunnycal.io` **A record at the server now**. Caddy needs it
resolving before first boot or certificate issuance fails.

---

## 2. Base system

```bash
ssh root@<server-ip>

apt update && apt upgrade -y
apt install -y ca-certificates curl gnupg lsb-release ufw fail2ban unattended-upgrades

timedatectl set-timezone UTC

# Application user. Postgres gets its own `postgres` user from the package.
adduser --disabled-password --gecos "" bunnycal
mkdir -p /home/bunnycal/.ssh
cp /root/.ssh/authorized_keys /home/bunnycal/.ssh/
chown -R bunnycal:bunnycal /home/bunnycal/.ssh
chmod 700 /home/bunnycal/.ssh && chmod 600 /home/bunnycal/.ssh/authorized_keys
```

Swapfile — OOM insurance for a box running a JVM and a database together:

```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile
mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab

# The JVM and Postgres both prefer RAM; only swap under real pressure.
sysctl -w vm.swappiness=10
echo 'vm.swappiness=10' >> /etc/sysctl.d/99-bunnycal.conf
```

Harden SSH — confirm your key works in a **second terminal** before closing this
one:

```bash
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart ssh

systemctl enable --now fail2ban
dpkg-reconfigure -plow unattended-upgrades
```

> `ufw` is installed but the **Hetzner cloud firewall is the authority**. Docker
> writes its own iptables rules via the `DOCKER-USER` chain and bypasses `ufw`,
> so a published container port is reachable even when `ufw` says otherwise.

---

## 3. Docker

From Docker's own repository, not Ubuntu's — the packaged version lags and the
Compose plugin differs.

```bash
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
  > /etc/apt/sources.list.d/docker.list

apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

usermod -aG docker bunnycal
docker --version && docker compose version
```

---

## 4. PostgreSQL 17

```bash
install -d /usr/share/postgresql-common/pgdg
curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
  --fail https://www.postgresql.org/media/keys/ACCC4CF8.asc

echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
  > /etc/apt/sources.list.d/pgdg.list

apt update
apt install -y postgresql-17 postgresql-contrib-17
```

Apply the tuned config (from the repo — clone it first if you prefer, see §6):

```bash
# conf.d/ so a package upgrade rewriting postgresql.conf cannot silently
# revert archive_mode and lose your PITR guarantee.
cp deploy/postgres/postgresql.conf.tuned /etc/postgresql/17/main/conf.d/10-bunnycal.conf
cp deploy/postgres/pg_hba.conf.example /etc/postgresql/17/main/pg_hba.conf
chown postgres:postgres /etc/postgresql/17/main/conf.d/10-bunnycal.conf

systemctl restart postgresql
```

Postgres will **fail to start** until pgBackRest exists, because `archive_command`
references it. That is expected — continue to §5 and restart after.

Create the role, database, and extensions:

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE bunnycal WITH LOGIN PASSWORD 'REPLACE_WITH_GENERATED_PASSWORD';
CREATE DATABASE bunnycal OWNER bunnycal;
SQL

# btree_gist is MANDATORY — the bookings partitions carry GiST EXCLUDE
# constraints and Flyway's V3_0 migration fails without it.
sudo -u postgres psql -d bunnycal <<'SQL'
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
SQL
```

Generate the password with `openssl rand -base64 32` and **save it to the
password manager now**.

---

## 5. pgBackRest

```bash
apt install -y pgbackrest

mkdir -p /var/log/pgbackrest /var/spool/pgbackrest /etc/pgbackrest
chown postgres:postgres /var/log/pgbackrest /var/spool/pgbackrest
chmod 750 /var/log/pgbackrest /var/spool/pgbackrest

cp deploy/pgbackrest/pgbackrest.conf.example /etc/pgbackrest/pgbackrest.conf
chown postgres:postgres /etc/pgbackrest/pgbackrest.conf
chmod 640 /etc/pgbackrest/pgbackrest.conf
```

Edit `/etc/pgbackrest/pgbackrest.conf` and replace every `REPLACE_ME`: bucket,
endpoint, region, key, secret, and `repo1-cipher-pass`.

```bash
openssl rand -base64 48   # repo1-cipher-pass — SAVE THIS FIRST
```

> ⚠️ **`repo1-cipher-pass` cannot be changed or recovered after the next
> command.** Lose it and every physical backup is permanently unreadable. Password
> manager **and** paper.

Confirm the data directory matches `pg1-path`, then initialise:

```bash
sudo -u postgres psql -Atc 'SHOW data_directory'   # expect /var/lib/postgresql/17/main

systemctl restart postgresql          # now succeeds: pgbackrest exists
sudo -u postgres pgbackrest --stanza=bunnycal stanza-create
sudo -u postgres pgbackrest --stanza=bunnycal check
```

`check` must pass before continuing. It forces a WAL segment switch and confirms
the segment reaches the repository — if this fails, archiving is broken and every
backup after it is worthless.

First full backup:

```bash
sudo -u postgres pgbackrest --stanza=bunnycal --type=full backup
sudo -u postgres pgbackrest --stanza=bunnycal info
```

Install the timers:

```bash
cp deploy/systemd/pgbackrest-*.{service,timer} /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now pgbackrest-full.timer pgbackrest-diff.timer pgbackrest-check.timer
systemctl list-timers 'pgbackrest-*'
```

Optional dead-man's switch for the hourly check:

```bash
echo 'PGBACKREST_HEALTHCHECK_URL=https://hc-ping.com/<uuid>' > /etc/pgbackrest/healthcheck.env
chown postgres:postgres /etc/pgbackrest/healthcheck.env
chmod 640 /etc/pgbackrest/healthcheck.env
```

---

## 6. The application

```bash
su - bunnycal
git clone <repo-url> /opt/bunnycal   # or deploy to the path in HETZNER_DEPLOY_PATH
cd /opt/bunnycal

cp .env.example .env
chmod 600 .env
```

Fill `.env`. The values that differ from the old managed-Postgres setup:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/bunnycal?sslmode=disable
SPRING_DATASOURCE_USERNAME=bunnycal
SPRING_DATASOURCE_PASSWORD=<the password from §4>

BACKUP_DATABASE_URL=postgresql://bunnycal:<password>@127.0.0.1:5432/bunnycal?sslmode=disable
```

Everything else carries over unchanged — **including
`CALENDAR_TOKEN_ENCRYPTION_KEY_BASE64`, which must be the existing value.**

```bash
docker compose up -d
docker compose logs -f bunnycal-api    # watch Flyway apply the migrations
```

Then the nightly logical dump. It needs `age` (encryption) and `rclone`
(upload), neither of which is installed yet:

```bash
exit    # back to root
apt install -y age rclone postgresql-client-17
```

Configure the rclone remote **as the `bunnycal` user** — the backup service runs
as `bunnycal`, so a remote configured under root is invisible to it:

```bash
sudo -u bunnycal rclone config
#   n) New remote
#   name> s3-eu-north
#   Storage> s3        →  provider> AWS
#   region> eu-north-1
#   access_key_id / secret_access_key: the same scoped IAM key
```

Verify it actually works before relying on it:

```bash
sudo -u bunnycal rclone lsd s3-eu-north:bunnycal-backups
```

Now install the timer:

```bash
mkdir -p /var/backups/bunnycal && chown bunnycal:bunnycal /var/backups/bunnycal
cp /opt/bunnycal/deploy/systemd/bunnycal-backup.* /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now bunnycal-backup.timer

systemctl start bunnycal-backup.service
journalctl -u bunnycal-backup -f
```

---

## 7. Verify

```bash
# TLS + health. A valid certificate proves port 80 reached Let's Encrypt.
curl -sS https://api.bunnycal.io/actuator/health

# Only 22/80/443 open; the database must NOT be reachable.
nmap -Pn <server-ip>
psql -h <server-ip> -U bunnycal bunnycal      # must fail to connect

# Memory: API near its 4g cap, Postgres holding its share, host with headroom.
docker stats --no-stream
free -h

# Archiving is live. Idle 6 minutes, then confirm a new WAL segment arrived —
# this proves archive_timeout works, which IS the 5-minute RPO guarantee.
sudo -u postgres pgbackrest --stanza=bunnycal info
```

### The PITR drill — do this before real users exist

A backup that has never been restored is hope, not a backup.

```bash
sudo -u postgres psql -d bunnycal -c \
  "CREATE TABLE dr_drill AS SELECT now() AS marker;"
date -u +'%Y-%m-%d %H:%M:%S+00'          # note this timestamp

sleep 120
sudo -u postgres psql -d bunnycal -c "DROP TABLE dr_drill;"

# Restore to just before the drop, into a scratch cluster on port 5433.
sudo -u postgres /opt/bunnycal/scripts/restore/pitr-restore.sh '<timestamp>'

# The table must exist here even though it is gone from the live database.
psql -p 5433 bunnycal -c 'SELECT * FROM dr_drill;'
```

Record the wall-clock recovery time in
[`disaster-recovery.md`](disaster-recovery.md). Then tear down:

```bash
sudo -u postgres /usr/lib/postgresql/17/bin/pg_ctl -D /var/lib/postgresql/restore-scratch stop
sudo rm -rf /var/lib/postgresql/restore-scratch
sudo -u postgres psql -d bunnycal -c 'DROP TABLE IF EXISTS dr_drill;'
```

Repeat monthly.

---

## 8. Hand off to CI

Update the GitHub `production` environment secrets:

| Secret | Value |
|---|---|
| `HETZNER_HOST` | the new server IP |
| `HETZNER_USER` | `bunnycal` |
| `HETZNER_DEPLOY_PATH` | `/opt/bunnycal` |
| `PRODUCTION_ENV_FILE` | the **complete** contents of the `.env` written in §6 |

`PRODUCTION_ENV_FILE` is the source of truth — the deploy writes it to the VM on
every run, so a change made only on the box is silently reverted at the next
deploy.

```bash
gh workflow run deploy-prod.yml
```

---

## 9. Monitoring

Three healthchecks.io checks plus a disk alert:

| Check | Signal | Period |
|---|---|---|
| Uptime | GET `https://api.bunnycal.io/actuator/health` | 1 min |
| pgBackRest archive | ping from `pgbackrest-check.service` | 1 h, 2 h grace |
| Nightly dump | ping from `bunnycal-backup.service` | 1 day, 6 h grace |
| Disk | alert at 80% on `/` | — |

The disk alert is not optional. A full disk stops WAL archiving, and Postgres
refuses writes rather than lose WAL. On a box sharing a JVM, its logs, and a
database, that is the most likely way this setup breaks.
