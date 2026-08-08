package org.ihtsdo.rvf.core.service.util;

import com.facebook.presto.sql.parser.StatementSplitter;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.dbcp.BasicDataSource;
import org.ihtsdo.otf.rest.exception.BusinessServiceException;
import org.ihtsdo.otf.RF2Constants;
import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.importer.AssertionGroupImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import javax.naming.ConfigurationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MySqlQueryTransformer {
    private final Logger logger = LoggerFactory.getLogger(MySqlQueryTransformer.class);

    private static final String FAILED_TO_FIND_RVF_DB_SCHEMA = "Failed to find rvf db schema for ";

    private String deltaTableSuffix = "d";
    private String snapshotTableSuffix = "s";
    private String fullTableSuffix = "f";
    private static final String DEFAULT_DELIMITER = ";";
    private static final String DELIMITER_REGEX_PATTERN = "^[ ]*(delimiter|DELIMITER)";

    public List<String> transformSql(String[] parts, MysqlExecutionConfig config, final Map<String, String> configMap)
            throws ConfigurationException {

        logger.info("Config Map contains " + configMap.entrySet().stream().map(e -> e.getKey() + " : " + e.getValue()).collect(Collectors.joining(",")));

        List<String> result = new ArrayList<>();
        String prospectiveSchema = config.getProspectiveVersion();
        String previousReleaseSchema = config.getPreviousVersion();
        String dependencyReleaseSchema = config.getExtensionDependencyVersion();

        //We need both these schemas to exist
        if (prospectiveSchema == null) {
            throw new ConfigurationException (FAILED_TO_FIND_RVF_DB_SCHEMA + prospectiveSchema);
        }

        if (config.isReleaseValidation() && !config.isFirstTimeRelease() && previousReleaseSchema == null) {
            throw new ConfigurationException (FAILED_TO_FIND_RVF_DB_SCHEMA + previousReleaseSchema);
        }

        final String[] nameParts = config.getProspectiveVersion().split("_");
        String version = (nameParts.length >= 3 ? nameParts[2] : "NOT_SUPPLIED");
        String includedModules = config.getIncludedModules().stream().collect(Collectors.joining(","));
        String defaultModuleId = StringUtils.hasLength(config.getDefaultModuleId()) ? config.getDefaultModuleId() : (nameParts.length >= 2 ? AssertionGroupImporter.ProductName.toModuleId(nameParts[1]) : "NOT_SUPPLIED");
        for( String part : parts) {
            if ((part.contains("<PREVIOUS>") && previousReleaseSchema == null)
                    || (part.contains("<DEPENDENCY>") && dependencyReleaseSchema == null)) {
                continue;
            }

            logger.debug("Original sql statement: {}", part);
            // remove all SQL comments - //TODO might throw errors for -- style comments
            final Pattern commentPattern = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
            part = commentPattern.matcher(part).replaceAll("");
            // replace all substitutions for exec
            part = part.replaceAll("<RUNID>", String.valueOf(config.getExecutionId()));
            part = part.replaceAll("<ASSERTIONUUID>", configMap.get("<ASSERTIONUUID>"));
            part = part.replaceAll("<MODULEID>", defaultModuleId);
            // <MODULEIDS> and <INCLUDED_MODULES> are the same substitution under
            // two names - this fork predates IHTSDO's rename. Both are applied so
            // the transformer serves either corpus. As at 2026-08-08 <MODULEIDS>
            // has zero uses in either corpus while <INCLUDED_MODULES> has three
            // in IHTSDO's, so the rename is the live name; <MODULEIDS> is kept
            // only because removing it is a separate cleanup with its own risk.
            part = part.replaceAll("<MODULEIDS>", includedModules);
            part = part.replaceAll("<INCLUDED_MODULES>", includedModules);
            // Added by IHTSDO after this fork diverged; used by one assertion in
            // their corpus. Without it that assertion executes with the literal
            // placeholder still in the SQL.
            part = part.replaceAll("<INTERNATIONAL_MODULES>", RF2Constants.SCTID_CORE_MODULE);
            part = part.replaceAll("<VERSION>", version);
            // watch out for any 's that users might have introduced
            part = part.replaceAll("qa_result", configMap.get("qa_result"));
            part = part.replaceAll("<PROSPECTIVE>", prospectiveSchema);
            part = part.replaceAll("<TEMP>", prospectiveSchema);
            if (previousReleaseSchema != null) {
                part = part.replaceAll("<PREVIOUS>", previousReleaseSchema);
            }
            if (dependencyReleaseSchema != null) {
                part = part.replaceAll("<DEPENDENCY>", dependencyReleaseSchema);
            }
            part = part.replaceAll("<DELTA>", deltaTableSuffix);
            part = part.replaceAll("<SNAPSHOT>", snapshotTableSuffix);
            part = part.replaceAll("<FULL>", fullTableSuffix);
            part = part.replaceAll(Pattern.quote("[[:<:]]"),"\\\\b" );
            part = part.replaceAll(Pattern.quote("[[:>:]]"),"\\\\b" );
            for(Map.Entry<String, String> configMapEntry: configMap.entrySet()){
                if (configMapEntry.getKey().matches("^<[^>]+>$")) {
                    part = part.replaceAll(configMapEntry.getKey(), configMapEntry.getValue());
                }
            }
            part.trim();
            logger.debug("Transformed sql statement: {}", part);
            result.add(part);
        }
        return result;
    }
    /**
     * Convert given sql file content to multiple statements
     * @param sqlFileContent
     * @return
     */
    public List<String> transformToStatements(String sqlFileContent) throws BusinessServiceException {
        String delimiter = DEFAULT_DELIMITER;
        List<String> result = new ArrayList<>();
        String[] sqlChunks = sqlFileContent.trim().split(DELIMITER_REGEX_PATTERN, Pattern.MULTILINE);
        for (int i = 0; i < sqlChunks.length; i++) {
            String sqlChunk = sqlChunks[i].trim();
            if (!sqlChunk.isEmpty()) {
                if (i > 0) {
                    delimiter = sqlChunk.trim().replaceAll("(?s)^([^ \r\n]+).*$", "$1");
                    sqlChunk = sqlChunk.trim().replaceAll("(?s)^[^ \r\n]+(.*)$", "$1").trim();
                }
                if (!sqlChunk.isEmpty()) {
                    logger.debug("Executing pre-requisite SQL: " + sqlChunk);
                    final StatementSplitter splitter = new StatementSplitter(sqlChunk, ImmutableSet.of(delimiter));
                    if (splitter.getCompleteStatements() == null || splitter.getCompleteStatements().isEmpty()) {
                        String errorMsg = String.format("SQL statements not ending with %s %s",delimiter, sqlChunk);
                        logger.error( errorMsg);
                        throw new BusinessServiceException(errorMsg);
                    }
                    result= splitter.getCompleteStatements().stream().map(s -> s.statement()).collect(Collectors.toList());

                }
            }

        }
        return result;
    }
}
