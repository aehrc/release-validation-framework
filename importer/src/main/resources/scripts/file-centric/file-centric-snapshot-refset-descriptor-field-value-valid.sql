/***********************
file-centric-snapshot-refset-descriptor-field-value-valid.sql

- Verify that RefsetDescriptor files contain only rows with referencedComponentId = < 900000000000455006 |Reference set (foundation metadata concept)|
- Verify that RefsetDescriptor files contain only rows with attributeDescription = < 900000000000457003 |Reference set attribute (foundation metadata concept)|
- Verify that RefsetDescriptor files contain only rows with attributeType = < 900000000000459000 |Attribute type (foundation metadata concept)|

 ***********************/

call validateValidDescendantsInRefsetDescriptorSnapshot_procedure(<RUNID>,'<ASSERTIONUUID>',
'refsetdescriptor_s','referencedcomponentid','900000000000455006', '= < 900000000000455006 | Reference set (foundation metadata concept)|');

call validateValidDescendantsInRefsetDescriptorSnapshot_procedure(<RUNID>,'<ASSERTIONUUID>',
'refsetdescriptor_s','attributedescription','900000000000457003', ' = < 900000000000457003 | Reference set attribute (foundation metadata concept)|');

call validateValidDescendantsInRefsetDescriptorSnapshot_procedure(<RUNID>,'<ASSERTIONUUID>',
'refsetdescriptor_s','attributetype','900000000000459000', '= < 900000000000459000 | Attribute type (foundation metadata concept)|');