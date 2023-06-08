drop function if exists get_cr_ADRS_PT;
create function get_cr_ADRS_PT(candidate bigint) returns varchar(4500)
BEGIN RETURN (SELECT da.term FROM langrefset_active as lr join description_active as da
                                                               on da.id = lr.referencedcomponentid WHERE lr.acceptabilityid = 900000000000548007 and conceptid = candidate); END;

drop function if exists get_cr_FSN;
create function get_cr_FSN(candidate bigint) returns varchar(4500)
BEGIN RETURN (SELECT term FROM description_active where conceptId = candidate and typeId = 900000000000003001 and languageCode = 'en'); END;

drop function if exists get_cr_PercentDefined;
create function get_cr_PercentDefined(refset bigint) returns decimal(6, 4)
BEGIN SET @refset = refset; SET @refsetSize = (select count(1) from simplerefset_active where refsetId = @refset); SET @definedCount = (select count(1) from concept_active where definitionStatusId = 900000000000073002 and id in (select referencedComponentId from simplerefset_active where refsetId = @refset));  RETURN CONVERT(@definedCount/ @refsetSize * 100,DECIMAL(6,4)); END;

drop function if exists isActiveConcept_cr;
create function isActiveConcept_cr(candidate varchar(20)) returns tinyint(1)
BEGIN  RETURN (select count(1) from concept_active where id = candidate);  END;

drop function if exists isActiveMemberOf_cr_refset;
create function isActiveMemberOf_cr_refset(candidate bigint, targetRefset bigint) returns int
BEGIN RETURN (select count(1) from simplerefset_active where refsetId = targetRefset and referencedComponentId = candidate); END;

drop function if exists isAncestorOf_cr;
create function isAncestorOf_cr(candidate bigint, target bigint) returns int
BEGIN RETURN (select count(1) from transitiveClosureTable where destinationId = candidate AND sourceId = target); END;

drop function if exists isChildOf_cr;
create function isChildOf_cr(candidate bigint, target bigint) returns int
BEGIN RETURN (select count(1) from relationship_active where sourceId = candidate AND destinationId = target AND typeId = 116680003); END;

drop function if exists isDescendentOf_cr;
create function isDescendentOf_cr(candidate bigint, target bigint) returns int
BEGIN RETURN (select count(1) from transitiveClosureTable where sourceId = candidate AND destinationId = target); END;

drop function if exists isKindOf_cr;
create function isKindOf_cr(candidate bigint, target bigint) returns int
BEGIN RETURN isDescendentOf_cr(candidate,target)+isConcept_cr(candidate,target); END;

drop function if exists isPositiveInteger;
create function isPositiveInteger(candidate varchar(20)) returns tinyint(1)
BEGIN CASE WHEN (SIGN(candidate) != 1) THEN RETURN FALSE; WHEN (ROUND(candidate) != candidate) THEN RETURN FALSE; ELSE RETURN TRUE; END CASE; END;

drop function if exists isValidComponentId_cr;
create function isValidComponentId_cr(candidate varchar(20)) returns tinyint(1)
BEGIN declare x int;  IF (CHAR_LENGTH(candidate) <19) THEN CASE SUBSTRING(candidate, -2, 1) WHEN 0 THEN SET x = (select count(1) from concept_<SNAPSHOT> where id = candidate); WHEN 1 THEN	SET x = (select count(1) from (select * from description_<SNAPSHOT> where id = candidate UNION select * from textdefinition_<SNAPSHOT> where id = candidate) AS D); WHEN 2 THEN SET x = (select count(1) from relationship_<SNAPSHOT> where id = candidate); ELSE RETURN FALSE; END CASE; ELSE return false; END IF;  IF x > 0 THEN return true; ELSE return false; END IF;   END;

drop function if exists isValidModuleId;
create function isValidModuleId(candidate varchar(20)) returns tinyint(1)
BEGIN  RETURN isDescendentOf_cr(candidate, 900000000000443000);  END;

drop function if exists isValidRefsetId_cr;
create function isValidRefsetId_cr(candidate varchar(20)) returns tinyint(1)
BEGIN  RETURN isDescendentOf_cr(candidate, 900000000000455006);  END;

drop function if exists isValidTimeFormat;
create function isValidTimeFormat(candidate varchar(30)) returns tinyint(1)
BEGIN  declare shortDate varchar(30); SET shortDate = DATE_FORMAT(candidate,'%Y%m%d');  CASE WHEN (CHAR_LENGTH(shortDate) != 8) THEN RETURN FALSE; WHEN (SUBSTRING(shortDate,1,4) < 2002 || SUBSTRING(shortDate,1,4) > 2099) THEN RETURN FALSE; WHEN (SUBSTRING(shortDate,5,2) < 1 || SUBSTRING(shortDate,5,2) > 12) THEN RETURN FALSE; WHEN (SUBSTRING(shortDate,7,2) < 1 || SUBSTRING(shortDate,7,2) > 31) THEN RETURN FALSE; WHEN (NOT isPositiveInteger(shortDate)) THEN RETURN FALSE; ELSE RETURN TRUE; END CASE;  END;

drop function if exists isValidUUID;
create function isValidUUID(candidate varchar(40)) returns tinyint(1)
BEGIN IF (LENGTH(candidate) != 36) THEN RETURN FALSE; ELSEIF (SUBSTRING(candidate,9,1) != '-') THEN RETURN FALSE; ELSEIF (SUBSTRING(candidate,14,1) != '-') THEN RETURN FALSE; ELSEIF (SUBSTRING(candidate,19,1) != '-') THEN RETURN FALSE; ELSEIF (SUBSTRING(candidate,24,1) != '-') THEN RETURN FALSE; ELSE RETURN TRUE; END IF;  END;

