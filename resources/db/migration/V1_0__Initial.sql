
-- Initial schema: tables

-- Defined flags:
-- 0x00000001 - enabled;
-- 0x00000002 - client side (for trace types);
-- 0x00000002 - force app (for registrations);
-- 0x00000004 - force env (for registrations);
-- 0x00000008 - used (by at least one trace);
-- 0x7fff0000 - pinned (fixed) for individual fields - for example, items edited
--              manually can't be changed from agents (not implemented yet);

-- Trace types: 0x01 - used to interpret and display trace data
CREATE TABLE ttype (
  id      INT NOT NULL auto_increment,        -- record ID
  name    VARCHAR(64) NOT NULL,      -- symbolic name
  comment VARCHAR(255),              -- comment (displayed verbatim in console)
  glyph   VARCHAR(64),               -- icon and color encoded as 'group/icon#color' (eg. awe/cube#red)
  descr   VARCHAR(255) NOT NULL,     -- template used to generate trace description (can use data from trace)
  flags   INT NOT NULL DEFAULT 1,    -- 0: enabled, bit 1: client
  dmin    INT NOT NULL DEFAULT 1000, -- start values for dynamic numeric trace type IDs
  PRIMARY KEY(id)
);

-- Applications: 0x02 - used to group hosts (distinguished attribute)
CREATE TABLE app (
  id      INT NOT NULL auto_increment,      -- record ID
  name    VARCHAR(64)  NOT NULL,   -- symbolic name
  comment VARCHAR(255),            -- comment (displayed verbatim in console)
  glyph   VARCHAR(64),             -- icon and color
  flags   INT NOT NULL DEFAULT 1,  -- 0: enabled
  PRIMARY KEY (id)
);

-- Environments: 0x03 - used to group hosts (distinguished attribute)
CREATE TABLE env (
  id      INT NOT NULL auto_increment,      -- record ID
  name    VARCHAR(64) NOT NULL,    -- symbolic name
  comment VARCHAR(255),            -- comment (displayed verbatim in console)
  glyph   VARCHAR(64),             -- icon and color
  flags   INT NOT NULL DEFAULT 1,  -- 0: enabled
  PRIMARY KEY (id)
);

-- Auxiliary attribute descriptors: 0x04
CREATE TABLE attrdesc (
  id      INT NOT NULL auto_increment,      -- record ID
  name    VARCHAR(64) NOT NULL,    -- attribute name (symbolic name)
  comment VARCHAR(255),            -- comment (displayed verbatim in console)
  glyph   VARCHAR(64),             -- icon and color
  flags   INT NOT NULL DEFAULT 1,  -- 0: enabled
  PRIMARY KEY (id)
);

-- Registered hosts: 0x05
CREATE TABLE host (
  id       INT NOT NULL auto_increment,     -- record ID
  name     VARCHAR(128) NOT NULL,  -- host name (zorka.hostname from zorka.properties)
  comment  VARCHAR(255),           -- comment (displayed verbatim)
  authkey  VARCHAR(64) NOT NULL,   -- authentication key
  env      INT NOT NULL,           -- environment UUID
  app      INT NOT NULL,           -- application UUID
  flags    INT NOT NULL DEFAULT 1, -- 0: enabled
  PRIMARY KEY (id)
);

-- Custom host attributes: 0x06
CREATE TABLE hostattr (
  id       INT NOT NULL auto_increment,     -- record ID
  hostid   INT  NOT NULL,  -- host UUID
  attrid   INT NOT NULL,  -- attribute description UUID
  attrval  VARCHAR(255) NOT NULL,  -- attribute value
  flags    INT NOT NULL DEFAULT 1, -- b0: enabled, b1: pinned
  PRIMARY KEY (id)
);

-- Registration rules: 0x07
CREATE TABLE hostreg (
  id      INT NOT NULL auto_increment,      -- record ID
  name    VARCHAR(128) NOT NULL,   -- rule name
  comment VARCHAR(255),            -- comment (displayed verbatim)
  regkey  VARCHAR(64) NOT NULL,    -- registration key
  env     INT,                     -- default environment
  app     INT,                     -- default application
  flags   INT NOT NULL DEFAULT 1,  -- b0: enabled, b1: force app, b2: force env
  PRIMARY KEY (id)
);

-- Users: 0x08
CREATE TABLE user (
  id       INT NOT NULL auto_increment,     -- record ID
  name     VARCHAR(128) NOT NULL,  -- login name
  fullname VARCHAR(255),           -- full name
  comment  VARCHAR(255),           -- some comment
  email    VARCHAR(255),           -- email address
  password VARCHAR(140),           -- hashed password (for local authentication): SSHA512
  flags    INT NOT NULL DEFAULT 1, -- b0: enabled, b1: admin
  PRIMARY KEY (id)
);

-- Groups: 0x09
CREATE TABLE groups (
  id      INT NOT NULL auto_increment,      -- record ID
  name    VARCHAR(128) NOT NULL,   -- group name
  comment VARCHAR(255),            -- comment
  PRIMARY KEY (id)
);

-- Access rights: 0x0a
CREATE TABLE access (
  id    INT NOT NULL auto_increment,     -- record ID
  grpid VARCHAR(36) NOT NULL,     -- group UUID
  objid VARCHAR(36),              -- object UUID (environment, aplication, host etc.), if null then applies to all objects;
  flags INT NOT NULL DEFAULT 1, -- :b0 enabled, :b1 read, :b2 write, :b3 admin
  PRIMARY KEY (id)
);

-- Configuration properties: 0x0f
CREATE TABLE props (
  id    INT NOT NULL auto_increment,       -- record ID
  propk VARCHAR(255) NOT NULL,    -- property key
  propv VARCHAR(255) NOT NULL,    -- property value
  PRIMARY KEY (id)
);
