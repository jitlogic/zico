
-- Initial schema: tables

-- Trace types: 0x01 - used to interpret and display trace data
-- Defined flags:
-- 0x00000001 - enabled;
-- 0x00000002 - client side (for trace types);
-- 0x00000002 - force app (for registrations);
-- 0x00000004 - force env (for registrations);
-- 0x00000008 - used (by at least one trace);
-- 0x7fff0000 - pinned (fixed) for individual fields - for example, items edited
--              manually can't be changed from agents (not implemented yet);
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
