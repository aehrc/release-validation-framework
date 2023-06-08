/* SKIPPED_TEST
	: Query references the amt_flat_file. We dont currently load that file into RVF

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 check that the datachecks SQL version of the AMT flat file contains no rows that arent present in the Java code version',
	null,
	null
from datachecks_amt_flat_file a left join generated_amt_flat_file b on a.CTPP_ID = b.CTPP_ID and a.CTPP_PT = b.CTPP_PT  and a.ARTG_ID = b.ARTG_ID and a.TPP_ID = b.TPP_ID and a.TPP_PT = b.TPP_PT and a.TPUU_ID = b.TPUU_ID and a.TPUU_PT = b.TPUU_PT and a.TPP_TP_ID = b.TPP_TP_ID and a.TPP_TP_PT = b.TPP_TP_PT and a.TPUU_TP_ID = b.TPUU_TP_ID and a.TPUU_TP_PT = b.TPUU_TP_PT and a.MPP_ID = b.MPP_ID and a.MPP_PT = b.MPP_PT and a.MPUU_ID = b.MPUU_ID and a.MPUU_PT = b.MPUU_PT and a.MP_ID = b.MP_ID and a.MP_PT = b.MP_PT where b.CTPP_ID is null;
*/

/* SKIPPED_TEST
	: Query references the amt_flat_file. We dont currently load that file into RVF

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 check that the Java code version of the flat file contains no rows that arent present in the datachecks SQL version',
	null,
	null
from generated_amt_flat_file b left join datachecks_amt_flat_file a on a.CTPP_ID = b.CTPP_ID and a.CTPP_PT = b.CTPP_PT and a.ARTG_ID = b.ARTG_ID and a.TPP_ID = b.TPP_ID and a.TPP_PT = b.TPP_PT and a.TPUU_ID = b.TPUU_ID and a.TPUU_PT = b.TPUU_PT and a.TPP_TP_ID = b.TPP_TP_ID and a.TPP_TP_PT = b.TPP_TP_PT and a.TPUU_TP_ID = b.TPUU_TP_ID and a.TPUU_TP_PT = b.TPUU_TP_PT and a.MPP_ID = b.MPP_ID and a.MPP_PT = b.MPP_PT and a.MPUU_ID = b.MPUU_ID and a.MPUU_PT = b.MPUU_PT and a.MP_ID = b.MP_ID and a.MP_PT = b.MP_PT where a.CTPP_ID is null;
*/

