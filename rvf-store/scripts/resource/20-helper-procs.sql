DROP PROCEDURE IF EXISTS TableIsPopulated;
CREATE PROCEDURE TableIsPopulated(IN tablename varchar(100))
BEGIN SET @s = CONCAT('SELECT COUNT(1) FROM ',tablename); PREPARE stmt FROM @s; EXECUTE stmt; END;

DROP PROCEDURE IF EXISTS create_transitiveClosureTable;
CREATE PROCEDURE create_transitiveClosureTable()

BEGIN

    DROP TABLE IF EXISTS transitiveClosureTable;

    CREATE TABLE transitiveClosureTable (sourceid BIGINT NOT NULL , destinationid BIGINT NOT NULL , PRIMARY KEY (sourceid, destinationid) ) ENGINE=MyISAM DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
    INSERT INTO transitiveClosureTable (sourceid, destinationid)
    SELECT distinct sourceid, destinationid from relationship_active where typeid = 116680003;


    REPEAT

        insert into transitiveClosureTable (sourceid, destinationid)
        SELECT distinct b.sourceid, a.destinationid from transitiveClosureTable a
                                                             join transitiveClosureTable b on a.sourceid = b.destinationid
                                                             left join transitiveClosureTable c on c.sourceid = b.sourceid and c.destinationid = a.destinationid
        where c.sourceid is null;
        set @x = row_count();

    UNTIL @x = 0 END REPEAT;

    create index idx_transitiveClosureTable_sourceid on transitiveClosureTable (sourceid);
    create index idx_transitiveClosureTable_destinationid on transitiveClosureTable (destinationid);

    create unique index uidx_transitiveClosureTable_sd on transitiveClosureTable (sourceId,destinationid);
    create unique index uidx_transitiveClosureTable_ds on transitiveClosureTable (destinationid,sourceId);

END;
CALL create_transitiveClosureTable();