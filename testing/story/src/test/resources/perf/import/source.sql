CREATE TABLE PEOPLE (
  THE_NAME VARCHAR(255) NOT NULL,
  GIVEN_NAME VARCHAR(255),
  FAMILY_NAME VARCHAR(255),
  EMAIL_ADDRESS VARCHAR(255),
  EMPLOYEE_NUMBER VARCHAR(255) NOT NULL,
  ACTIVATION_TIMESTAMP TIMESTAMP,
  ADMINISTRATOR_DESCRIPTION VARCHAR(512),
  DEPARTMENT_NUMBER VARCHAR(255),
  GID_NUMBER int4(10),
  LAST_ROLE_CODE VARCHAR(255),
  LIFE_CYCLE_STAGE VARCHAR(255),
  EMAIL_ALIAS VARCHAR(1024),
  ROLE_CODE VARCHAR(255),
  SERVICE VARCHAR(255),
  STUDENT_FACULTY_CODE VARCHAR(255),
  UID_NUMBER int4(10),
  ADMINISTRATIVE_STATUS VARCHAR(255),
  CREATE_TIMESTAMP DATE,
  PRIMARY KEY (THE_NAME)
);