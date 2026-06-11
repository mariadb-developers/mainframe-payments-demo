-- Replication-privileged user for the Debezium source connector.
-- The connector configuration in cdc_connectors.mainframe-to-gg expects this
-- user's credentials to live in the `mainframe-to-gg-debezium-auth` k8s Secret
-- (created out-of-band by the operator). Username/password placeholders here
-- match what the secret should contain.

-- TODO before first deploy: replace the password with the value you'll put in
-- the k8s Secret. This file is committed to source control — do not put real
-- production secrets here.
CREATE ROLE debezium WITH LOGIN REPLICATION PASSWORD 'debezium-password-replace-me';

GRANT CONNECT ON DATABASE payments TO debezium;
GRANT USAGE ON SCHEMA public TO debezium;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO debezium;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO debezium;

-- Pre-create the Debezium publication explicitly. The connector is configured
-- with publication.autocreate.mode=disabled so it does NOT need CREATE-on-database
-- privilege at runtime. The publication name must match
-- cdc_connectors.<name>.debezium.publication_name in demo-config.yaml.
CREATE PUBLICATION mainframe_payments_pub FOR TABLE public.customer, public.account, public.transaction;
