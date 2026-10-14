-- Runs once when the postgres container's data volume is first created.
-- Each microservice owns its own database — no service ever queries another's schema directly.
CREATE DATABASE authdb;
CREATE DATABASE productdb;
CREATE DATABASE orderdb;
CREATE DATABASE paymentdb;
