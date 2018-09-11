/********************************************************************************
	component-centric-snapshot-description-root-concept-synonym-reference-new-release.sql

	Assertion:
	Verify that the root concept synonym is now referencing the latest new release

********************************************************************************/
    insert into qa_result (runid, assertionuuid, concept_id, details)
    select
    		<RUNID>,
            '<ASSERTIONUUID>',
            result.id,
            result.expression
            from
            (select a.id,
                     concat('Concept id = ',a.id,', root concept synonym is not referencing the latest new release') expression
                     from curr_description_s a
                     where a.conceptid= '138875005'
                     and a.typeid = '900000000000013009'
                     and a.active = 1
                     and a.effectiveTime <> '<CURR_EFFECTIVE_TIME>'
                     and '<CURR_EFFECTIVE_TIME>' <> ''
                     and a.term like concat('%SNOMED Clinical Terms version: %<CURR_EFFECTIVE_TIME>%')) as result;
    commit;

    insert into qa_result (runid, assertionuuid, concept_id, details)
    select
    	<RUNID>,
    	'<ASSERTIONUUID>',
        result.id,
        result.expression
        from
        (select a.id,
                  concat('Concept id = ',a.id,', root concept synonym is now referencing the latest new release but is in inactivate state') expression
                  from curr_description_s a
                  where a.conceptid= '138875005'
                  and a.typeid = '900000000000013009'
                  and a.active = 0
                  and a.effectivetime = '<CURR_EFFECTIVE_TIME>'
                  and '<CURR_EFFECTIVE_TIME>' <> ''
                  and a.term like concat('%SNOMED Clinical Terms version: %<CURR_EFFECTIVE_TIME>%')) as result;
    commit;

    insert into qa_result (runid, assertionuuid, concept_id, details)
        select
        	<RUNID>,
        	'<ASSERTIONUUID>',
            result.id,
            result.expression
            from
            (select a.id,
                      concat('Concept id = ',a.id,', root concept synonym is not valid') expression
                      from curr_description_s a
                      where a.conceptid= '138875005'
                      and a.typeid = '900000000000013009'
                      and a.active = 1
                      and a.effectivetime = '<CURR_EFFECTIVE_TIME>'
                      and '<CURR_EFFECTIVE_TIME>' <> ''
                      and a.term not like concat('%SNOMED Clinical Terms version: %<CURR_EFFECTIVE_TIME>%')) as result;
    commit;
