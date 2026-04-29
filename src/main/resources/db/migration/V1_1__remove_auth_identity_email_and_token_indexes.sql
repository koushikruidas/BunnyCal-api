-- 1) Data consistency validation: auth_identities.email must match users.email
-- Run this query and resolve any rows returned before applying the column drop.
-- select ai.id, ai.email as auth_email, u.email as user_email
-- from auth_identities ai
-- join users u on u.id = ai.user_id
-- where ai.email is distinct from u.email;
do $$
begin
    if exists (
        select 1
        from auth_identities ai
        join users u on u.id = ai.user_id
        where ai.email is distinct from u.email
    ) then
        raise exception 'Mismatch detected between auth_identities.email and users.email';
    end if;
end
$$;

-- 2) Drop duplicate email column after discrepancies are resolved.
alter table auth_identities drop column if exists email;

-- 3) Align refresh token indexes with active-token lookup and rotation.
drop index if exists idx_refresh_token_user_id;
create unique index if not exists idx_refresh_token_token on refresh_tokens (token_hash);
create index if not exists idx_refresh_token_user on refresh_tokens (user_id);

-- 4) Align provider+provider_user uniqueness naming.
alter table auth_identities drop constraint if exists uk_auth_identity_provider_user_id;
alter table auth_identities add constraint uk_provider_user unique (provider, provider_user_id);
