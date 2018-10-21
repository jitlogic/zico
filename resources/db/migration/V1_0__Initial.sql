
-- Initial schema: tables

-- Trace types: 0x01 - used to interpret and display trace data
CREATE TABLE ttype (
  uuid    VARCHAR(36) NOT NULL,      -- record UUID
  name    VARCHAR(64) NOT NULL,      -- symbolic name
  comment VARCHAR(255),              -- comment (displayed verbatim in console)
  glyph   VARCHAR(64),               -- icon and color encoded as 'group/icon#color' (eg. awe/cube#red)
  descr   VARCHAR(255) NOT NULL,     -- template used to generate trace description (can use data from trace)
  flags   INT NOT NULL DEFAULT 1,    -- 0: enabled, bit 1: client
  dmin    INT NOT NULL DEFAULT 1000, -- start values for dynamic numeric trace type IDs
  PRIMARY KEY(uuid)
);

-- Applications: 0x02 - used to group hosts (distinguished attribute)
CREATE TABLE app (
  uuid    VARCHAR(36)  NOT NULL,     -- record UUID
  name    VARCHAR(64)  NOT NULL,     -- symbolic name
  comment VARCHAR(255),              -- comment (displayed verbatim in console)
  glyph   VARCHAR(64),               -- icon and color
  flags   INT NOT NULL DEFAULT 1,    -- 0: enabled
  PRIMARY KEY (uuid)
);

-- Environments: 0x03 - used to group hosts (distinguished attribute)
CREATE TABLE env (
  uuid    VARCHAR(36) NOT NULL,      -- record UUID
  name    VARCHAR(64) NOT NULL,      -- symbolic name
  comment VARCHAR(255),              -- comment (displayed verbatim in console)
  glyph   VARCHAR(64),               -- icon and color
  flags   INT NOT NULL DEFAULT 1,    -- 0: enabled
  PRIMARY KEY (uuid)
);

-- Auxiliary attribute descriptors: 0x04
CREATE TABLE attrdesc (
  uuid    VARCHAR(36) NOT NULL,      -- record UUID
  name    VARCHAR(64) NOT NULL,     -- attribute name (symbolic name)
  comment VARCHAR(255),              -- comment (displayed verbatim in console)
  glyph   VARCHAR(64),               -- icon and color
  flags   INT NOT NULL DEFAULT 1,    -- 0: enabled
  PRIMARY KEY (uuid)
);

-- Registered hosts: 0x05
CREATE TABLE host (
  uuid     VARCHAR(36) NOT NULL,      -- record UUID
  name     VARCHAR(128) NOT NULL,     -- host name (zorka.hostname from zorka.properties)
  comment  VARCHAR(255),              -- comment (displayed verbatim)
  authkey  VARCHAR(64) NOT NULL,      -- authentication key
  env      VARCHAR(36) NOT NULL,      -- environment UUID
  app      VARCHAR(36) NOT NULL,      -- application UUID
  flags    INT NOT NULL DEFAULT 1,    -- 0: enabled
  PRIMARY KEY (uuid)
);

-- Custom host attributes: 0x06
CREATE TABLE hostattr (
  uuid     VARCHAR(36)  NOT NULL,    -- record UUID
  hostuuid VARCHAR(36)  NOT NULL,    -- host UUID
  attruuid VARCHAR(36)  NOT NULL,    -- attribute description UUID
  attrval  VARCHAR(255) NOT NULL,    -- attribute value
  flags    INT NOT NULL DEFAULT 1,   -- b0: enabled, b1: pinned
  PRIMARY KEY (uuid)
);


-- Registration rules: 0x07
CREATE TABLE hostreg (
  uuid   VARCHAR(36) NOT NULL,   -- record UUID
  name   VARCHAR(128) NOT NULL,  -- rule name
  comment VARCHAR(255),          -- comment (displayed verbatim)
  regkey VARCHAR(64) NOT NULL,   -- registration key
  env    VARCHAR(36),            -- default environment
  app    VARCHAR(36),            -- default application
  flags  INT NOT NULL DEFAULT 1, -- b0: enabled, b1: force app, b2: force env
  PRIMARY KEY (uuid)
);

-- Users: 0x08
CREATE TABLE user (
  uuid     VARCHAR(36) NOT NULL,   -- record UUID
  name     VARCHAR(128) NOT NULL,  -- login name
  fullname VARCHAR(255),           -- full name
  comment  VARCHAR(255),           -- some comment
  email    VARCHAR(255),           -- email address
  password VARCHAR(128),           -- hashed password (for local authentication): SSHA512
  flags    INT NOT NULL DEFAULT 1, -- b0: enabled, b1: admin
  PRIMARY KEY (uuid)
);

-- Groups: 0x09
CREATE TABLE groups (
  uuid    VARCHAR(36) NOT NULL,    -- record UUID
  name    VARCHAR(128) NOT NULL,   -- group name
  comment VARCHAR(255),            -- comment
  PRIMARY KEY (uuid)
);


-- Access rights: 0x0a
CREATE TABLE access (
  uuid    VARCHAR(36) NOT NULL,    -- record UUID
  grpuuid VARCHAR(36) NOT NULL,    -- group UUID
  objuuid VARCHAR(36),             -- object UUID (environment, aplication, host etc.), if null then applies to all objects;
  flags   INT NOT NULL DEFAULT 1,  -- :b0 enabled, :b1 read, :b2 write, :b3 admin
  PRIMARY KEY (uuid)
);


-- Configuration properties: 0x0f
CREATE TABLE props (
  uuid VARCHAR(36) NOT NULL,       -- record UUID
  propk VARCHAR(255) NOT NULL,     -- property key
  propv VARCHAR(255) NOT NULL,     -- property value
  PRIMARY KEY (uuid)
);


-- Initial data: initial settings

-- Defined flags:
-- 0x00000001 - enabled;
-- 0x00000002 - client side (for trace types);
-- 0x00000002 - force app (for registrations);
-- 0x00000004 - force env (for registrations);
-- 0x7fff0000 - pinned (fixed) for individual fields - for example, items edited
--              manually can't be changed from agents (not implemented yet);


-- Default trace types
INSERT INTO ttype (uuid,flags,dmin,name,glyph,descr,comment) values
  ('21c00000-0101-0000-0001-000000000000', 1, 1000, 'HTTP',
   'awe/globe#blue', '${METHOD:REQ} ${URI} -> ${STATUS}', 'HTTP request handling'),
  ('21c00000-0101-0000-0002-000000000000', 3, 1000, 'HTTP_CLI',
   'awe/globe#darkgreen', '${METHOD} ${URL}', 'HTTP client request'),
  ('21c00000-0101-0000-0003-000000000000', 3, 1000, 'SQL',
   'awe/database#darkgreen', '${SQL}', 'SQL query'),
  ('21c00000-0101-0000-0004-000000000000', 3, 1000, 'LDAP',
   'awe/database', '${NAME}: ${FILTER}', 'LDAP query'),
  ('21c00000-0101-0000-0005-000000000000', 1, 1000, 'SOAP',
   'awe/cube#blue', '${URI}: ${HDR.IN.soapOperation} -> ${STATUS}', 'SOAP request handling'),
  ('21c00000-0101-0000-0006-000000000000', 3, 1000, 'SOAP_CLI',
   'awe/cube#darkgreen', '${URL}: ${HDR.OUT.soapOperation} -> ${STATUS}', 'SOAP client request'),
  ('21c00000-0101-0000-0007-000000000000', 1, 1000, 'EJB',
   'awe/cube#blue', '${CLASS}.${METHOD}', 'EJB request handling'),
  ('21c00000-0101-0000-0008-000000000000', 3, 1000, 'EJB_CLI',
   'awe/cube#darkgreen', '${CLASS}.${METHOD}', 'EJB client request'),
  ('21c00000-0101-0000-0009-000000000000', 1, 1000, 'REST',
   'awe/globe#blue', '${HTTP_METHOD} ${URI}', 'REST request handling'),
  ('21c00000-0101-0000-000a-000000000000', 3, 1000, 'REST_CLI',
   'awe/globe#darkgreen', '${CLASS}.${METHOD}', 'REST client request'),
  ('21c00000-0101-0000-000b-000000000000', 1, 1000, 'JMS',
   'awe/cube#blue', '${CLASS}.${METHOD}', 'JMS message handling'),
  ('21c00000-0101-0000-000c-000000000000', 1, 1000, 'QUARTZ',
   'awe/clock#darkgreen', '${GROUP} ${NAME}', 'Quartz Schedules'),
  ('21c00000-0101-0000-000f-000000000000', 1, 1000, 'CAMEL',
   'awe/globe#blue', '${CLASS} ${CONTEXT_NAME}', 'Apache CAMEL flow'),
  ('21c00000-0101-0000-0010-000000000000', 1, 1000, 'CAS',
   'awe/user-secret#blue', '${ACTION}', 'Jasig CAS action'),
  ('21c00000-0101-0000-0011-000000000000', 1, 1000, 'CAS_CLI',
   'awe/user-secret#darkgreen', '${CAS_URI}', 'Jasig CAS client'),
  ('21c00000-0101-0000-0012-000000000000', 1, 1000, 'MULE_CMP',
   'awe/cube#blue', '${APPLICATION} ${FLOW}', 'Mule ESB component'),
  ('21c00000-0101-0000-0013-000000000000', 1, 1000, 'MULE_DISP',
   'awe/cube#blue', '${APPLICATION} ${URI}', 'Mule ESB dispatcher'),
  ('21c00000-0101-0000-0014-000000000000', 1, 1000, 'MULE_FLOW',
   'awe/cube#blue', '${APPLICATION} ${FLOW}', 'Mule ESB flow'),
  ('21c00000-0101-0000-0015-000000000000', 3, 1000, 'CRYSTAL',
   'awe/cube#blue', '${RID}', 'Crystal Reports Remote Request'),
  ('21c00000-0101-0000-0016-000000000000', 3, 1000, 'FLEX',
   'awe/cube#blue', '${DEST}: ${MESSAGE_ID} (${CLIENT_ID})', 'FLEX Remote Service'),
  ('21c00000-0101-0000-0017-000000000000', 3, 1000, 'SPRING',
   'awe/cube#blue', '${CLASS|URI|SERVICE_URL}/${METHOD}', 'Spring invocation'),
  ('21c00000-0101-0000-0018-000000000000', 3, 1000, 'AMQP_SEND',
   'awe/paper-plane-empty#darkgreen', '${CONN}  -> ${EXCHANGE}', 'AMQP message sent.'),
  ('21c00000-0101-0000-1000-000000000000', 3, 1000, 'SPRING',
   'awe/paper-plane-empty#blue', '${CONN}  <- ${EXCHANGE}', 'AMQP message received.');


-- Default application
INSERT INTO app (uuid, name, glyph, comment) VALUES
  ('21c00000-0201-0000-0001-000000000000', 'DSC', 'awe/cube', 'Discovered Application');

-- Default environments
INSERT INTO env (uuid, name, glyph, comment) VALUES
  ('21c00000-0301-0000-0001-000000000000', 'PRD', 'awe/network', 'Production'),
  ('21c00000-0301-0000-0002-000000000000', 'UAT', 'awe/network', 'User Acceptance Tests'),
  ('21c00000-0301-0000-0003-000000000000', 'SIT', 'awe/network', 'System Integration Testing'),
  ('21c00000-0301-0000-0004-000000000000', 'MNT', 'awe/network', 'Maintenance'),
  ('21c00000-0301-0000-0005-000000000000', 'DEV', 'awe/network', 'Development'),
  ('21c00000-0301-0000-0006-000000000000', 'TST', 'awe/network', 'Test');

-- Default host registration will register everything under as discovered application in Test environment;
INSERT INTO hostreg (uuid, name, regkey, flags, app, env, comment) VALUES
  ('21c00000-0701-0000-0001-000000000000', 'ZORKA', 'zorka', 1,
   '21c00000-0201-0000-0001-000000000000', '21c00000-0301-0000-0006-000000000000',
   'Default registration (please modify).');

-- Default user
INSERT INTO user (uuid, name, fullname, comment, email, password, flags) VALUES
  ('21c00000-0801-0000-0001-000000000000', 'admin', 'Zorka Administrator', '', 'admin@mycompany.com', 'zico', 3);


-- TODO default config properties

