# VM Provisioning Runbook

Builds the BunnyCal production host from a stock image. Follow top to bottom;
each step assumes the previous one succeeded.

**Target:** one Hetzner **CX43** (8 vCPU / 16 GB / 160 GB) in **hel1 (Helsinki)**,
**Ubuntu 26.04 LTS** (`resolute`).

> Both the Docker and PGDG repositories publish a `resolute` suite, so
> `$(lsb_release -cs)` works unmodified below. Verified: `pgbackrest` ships as
> `2.59.0-1.pgdg26.04+1`.
>
> ⚠️ **Ubuntu 26.04 defaults to PostgreSQL 18.** This deployment pins **17** —
> see §4 for why — so never run a bare `apt install postgresql`, which would
> silently install 18 and leave you with two clusters on different ports.

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
**Ubuntu 26.04**, your SSH key.

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

# Hetzner's Ubuntu image ships without the `universe` component enabled, and
# awscli, rclone and age all live there. Without this, `apt install awscli`
# fails with "no installation candidate" even though the package exists.
apt install -y software-properties-common
add-apt-repository -y universe

apt update && apt upgrade -y

# ca-certificates/curl/gnupg/lsb-release are prerequisites for the Docker and
# PGDG repositories added in §3 and §4 — TLS verification, key fetching, and
# the `lsb_release -cs` codename those repo URLs interpolate.
apt install -y ca-certificates curl gnupg lsb-release fail2ban unattended-upgrades

timedatectl set-timezone UTC

# Application user. Postgres gets its own `postgres` user from the package.
adduser --disabled-password --gecos "" bunnycal
mkdir -p /home/bunnycal/.ssh
cp /root/.ssh/authorized_keys /home/bunnycal/.ssh/
chown -R bunnycal:bunnycal /home/bunnycal/.ssh
chmod 700 /home/bunnycal/.ssh && chmod 600 /home/bunnycal/.ssh/authorized_keys

# ⚠️ REQUIRED before the SSH hardening below. Without sudo rights here,
# disabling root login leaves NO way to escalate on this box and the only
# way back in is Hetzner's rescue console.
usermod -aG sudo bunnycal

# `--disabled-password` above means there is no password for sudo to check.
# Set one now: SSH stays key-only (PasswordAuthentication no blocks password
# LOGINS regardless), this only gives sudo a second factor if the key leaks.
passwd bunnycal
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

### Harden SSH — the one step that can lock you out

**Keep the root session open** throughout. In a **second terminal**, prove the
replacement access path fully works first:

```bash
ssh bunnycal@<server-ip>
groups                # must include: sudo
sudo whoami           # must print: root
```

`sudo whoami` returning `root` is the gate — it proves you can still administer
the box once root login is gone. A fresh login is required after `usermod`,
since group membership is only evaluated at login. **Do not continue until both
succeed.**

Then, back in the root session:

```bash
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config

# Validate BEFORE restarting. A syntax error plus a restart means sshd does not
# come back, and the open session is the only thing that can still fix it.
sshd -t

systemctl restart ssh
# Ubuntu 26.04 may use socket activation; if the change seems not to apply:
systemctl restart ssh.socket

systemctl enable --now fail2ban
dpkg-reconfigure -plow unattended-upgrades
```

Finally, from the second terminal, confirm `bunnycal` still connects **and** that
`ssh root@<server-ip>` is now refused. Only then close the root session.

> **The Hetzner cloud firewall is the authority — `ufw` is deliberately not
> installed.** It would duplicate rules already enforced at the network edge,
> and it cannot police Docker's published ports anyway: Docker writes its own
> iptables rules via the `DOCKER-USER` chain and bypasses `ufw` entirely.
>
> Confirm the firewall is **attached to the server**, not merely created — an
> unattached firewall looks configured in the console but filters nothing.
>
> Run this **from your laptop, not the server** — the point is to see what the
> outside world can reach:
>
> ```bash
> nc -zv -w5 <server-ip> 22      # succeeds
> nc -zv -w5 <server-ip> 5432    # MUST time out
> nc -zv -w5 <server-ip> 6379    # MUST time out
> ```
>
> A **timeout** is the correct result and is better than "connection refused":
> it means the firewall is dropping packets silently. "Refused" would mean the
> packet reached the host and merely found nothing listening — which is not the
> same as being filtered.
>
> Nothing listens on 5432/6379 until §4 and §6, so this test only becomes
> meaningful once Postgres and Redis are actually running. Re-run it in §7.
>
> (`nmap -Pn <server-ip>` gives the same answer more thoroughly if you have it.
> Install it on your laptop, not on the server — a port scanner is not something
> to leave lying around on a production host.)
>
> This does not replace `listen_addresses = 'localhost'` in §4. The firewall
> protects the network edge; that setting means Postgres never binds a public
> interface at all, so the database stays closed by construction even if the
> firewall is later misconfigured.

---

## 3. Docker

From Docker's own repository, not Ubuntu's — the packaged version lags and the
Compose plugin differs.

Run as `bunnycal` with per-command `sudo`, not from a root shell. Steps later in
this runbook must run as a *specific* user (`rclone config` as `bunnycal`,
`pgbackrest` as `postgres`), and files written as root in §6 leave `.env` and the
repo unreadable by the user Compose actually runs as.

```bash
sudo install -m 0755 -d /etc/apt/keyrings

# `sudo` belongs on the WRITING half of each pipe. On the curl/echo half it
# would fetch as root but still write as bunnycal, which fails confusingly.
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo usermod -aG docker bunnycal
```

Log out and back in — group membership is only evaluated at login, so `docker`
fails with a socket permission error until you do:

```bash
exit
ssh bunnycal@<server-ip>

groups                        # expect: bunnycal sudo docker
docker --version && docker compose version
docker run --rm hello-world   # must work WITHOUT sudo
```

---

## 4. PostgreSQL 17

> **Why 17 and not the 18 that Ubuntu 26.04 defaults to.** The test suite runs
> against PostgreSQL 16 (`postgres:16-alpine` in Testcontainers). 17 is one
> major version from what is actually validated; 18 would be two, on an OS only
> months old, with a schema that leans on GiST `EXCLUDE` constraints over
> partitioned tables — precisely where planner and locking behaviour shifts
> between majors. PG18 also reworked I/O internals (async I/O), and pgBackRest
> has far more production mileage against 17.
>
> Nothing in the schema requires either version: the migrations use only
> `btree_gist`, `pgcrypto`, `EXCLUDE` constraints and partitioning. This is a
> risk decision, not a capability one. Revisit once the Testcontainers image
> moves forward.

```bash
sudo install -d /usr/share/postgresql-common/pgdg
sudo curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
  --fail https://www.postgresql.org/media/keys/ACCC4CF8.asc

echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
  | sudo tee /etc/apt/sources.list.d/pgdg.list

sudo apt update

# Explicit major version. A bare `apt install postgresql` on 26.04 installs 18.
sudo apt install -y postgresql-17 postgresql-contrib-17

# Confirm exactly one cluster, running 17 on 5432. If 18 also appears here,
# remove it now (`apt purge postgresql-18`) rather than after data exists —
# two clusters means the second silently takes port 5433 and every later
# command in this runbook targets the wrong one.
pg_lsclusters
```

Apply the tuned config. **This and §5 copy files out of the repository, so clone
it now** — the clone step in §6 is written up there only because that is where
`.env` is filled in:

```bash
sudo mkdir -p /opt/bunnycal
sudo chown bunnycal:bunnycal /opt/bunnycal
git clone <repo-url> /opt/bunnycal
cd /opt/bunnycal
```

Then, from `/opt/bunnycal`:

```bash
# conf.d/ so a package upgrade rewriting postgresql.conf cannot silently
# revert archive_mode and lose your PITR guarantee.
sudo cp deploy/postgres/postgresql.conf.tuned /etc/postgresql/17/main/conf.d/10-bunnycal.conf
sudo cp deploy/postgres/pg_hba.conf.example /etc/postgresql/17/main/pg_hba.conf
sudo chown postgres:postgres /etc/postgresql/17/main/conf.d/10-bunnycal.conf

sudo systemctl restart postgresql
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
sudo apt install -y pgbackrest

sudo mkdir -p /var/log/pgbackrest /var/spool/pgbackrest /etc/pgbackrest
sudo chown postgres:postgres /var/log/pgbackrest /var/spool/pgbackrest
sudo chmod 750 /var/log/pgbackrest /var/spool/pgbackrest

sudo cp deploy/pgbackrest/pgbackrest.conf.example /etc/pgbackrest/pgbackrest.conf
sudo chown postgres:postgres /etc/pgbackrest/pgbackrest.conf
sudo chmod 640 /etc/pgbackrest/pgbackrest.conf
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

sudo systemctl restart postgresql     # now succeeds: pgbackrest exists
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
sudo cp deploy/systemd/pgbackrest-*.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now pgbackrest-full.timer pgbackrest-diff.timer pgbackrest-check.timer
systemctl list-timers 'pgbackrest-*'
```

Optional dead-man's switch for the hourly check:

```bash
echo 'PGBACKREST_HEALTHCHECK_URL=https://hc-ping.com/<uuid>' \
  | sudo tee /etc/pgbackrest/healthcheck.env
sudo chown postgres:postgres /etc/pgbackrest/healthcheck.env
sudo chmod 640 /etc/pgbackrest/healthcheck.env
```

---

## 6. The application

Run these **as `bunnycal`**, not root. The deploy workflow and `docker compose`
both run as this user, so root-owned files here mean the app cannot read its own
`.env` later.

The repository was already cloned in §4. From `/opt/bunnycal`:

```bash
cd /opt/bunnycal

cp .env.example .env
chmod 600 .env

ls -l .env    # must show bunnycal bunnycal, not root root
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
apt install -y age rclone postgresql-client-17 awscli
```

`awscli` is optional — pgBackRest speaks S3 natively and the dump uses rclone,
so nothing in the backup path needs it. It is worth having for poking at the
bucket during an incident. All four packages live in `universe`, enabled in §2.

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

# --- FROM YOUR LAPTOP, not the server ---------------------------------------
# Now that Postgres and Redis are actually running, this test is meaningful:
# it proves they are unreachable from outside rather than merely not started.
nc -zv -w5 <server-ip> 5432    # MUST time out
nc -zv -w5 <server-ip> 6379    # MUST time out
psql -h <server-ip> -U bunnycal bunnycal      # must fail to connect
# nmap -Pn <server-ip>         # same check, more thorough, if installed

# Memory: API well under its 6g cap, Postgres ~2.5 GB, several GB page cache.
docker stats --no-stream
free -h

# Peak heap during the Flyway run — the app's highest-memory moment, and the
# one worth checking because it happens before there is traffic to observe.
# Expect well under the ~4.5 GB ceiling; if it lands close, raise mem_limit.
docker exec bunnycal-api jcmd 1 GC.heap_info

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
