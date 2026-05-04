-- 1) Data consistency validation: auth_identities.email must match users.email
-- Run this query and resolve any rows returned before applying the column drop.
-- select ai.id, ai.email as auth_email, u.email as user_email
-- from auth_identities ai
-- join users u on u.id = ai.user_id
-- where ai.email is distinct from u.email;
do $$
declare
    mismatch_exists boolean := false;
begin
    if to_regclass('public.auth_identities') is not null
       and to_regclass('public.users') is not null
       and exists (
           select 1
           from information_schema.columns
           where table_schema = 'public'
             and table_name = 'auth_identities'
             and column_name = 'email'
       ) then
        execute $sql$
            select exists (
                select 1
                from auth_identities ai
                join users u on u.id = ai.user_id
                where ai.email is distinct from u.email
            )
        $sql$ into mismatch_exists;
        if mismatch_exists then
            raise exception 'Mismatch detected between auth_identities.email and users.email';
        end if;
    end if;
end
$$;

-- 2) Drop duplicate email column after discrepancies are resolved.
do $$
begin
    if to_regclass('public.auth_identities') is not null then
        alter table auth_identities drop column if exists email;
    end if;
end
$$;

-- 3) Align refresh token indexes with active-token lookup and rotation.
do $$
begin
    if to_regclass('public.refresh_tokens') is not null then
        drop index if exists idx_refresh_token_user_id;
        create unique index if not exists idx_refresh_token_token on refresh_tokens (token_hash);
        create index if not exists idx_refresh_token_user on refresh_tokens (user_id);
    end if;
end
$$;

-- 4) Align provider+provider_user uniqueness naming.
do $$
begin
    if to_regclass('public.auth_identities') is not null then
        alter table auth_identities drop constraint if exists uk_auth_identity_provider_user_id;
        if not exists (
            select 1
            from pg_constraint
            where conname = 'uk_provider_user'
              and conrelid = 'public.auth_identities'::regclass
        ) then
            alter table auth_identities add constraint uk_provider_user unique (provider, provider_user_id);
        end if;
    end if;
end
$$;
