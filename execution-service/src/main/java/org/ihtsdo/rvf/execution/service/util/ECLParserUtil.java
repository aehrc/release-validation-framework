package org.ihtsdo.rvf.execution.service.util;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import apg.Grammar;
import apg.Parser;

/**
 * Created by NamLe on 5/16/2017.
 */
public class ECLParserUtil {
    private static final Logger logger = LoggerFactory.getLogger(ECLParserUtil.class);
    public static boolean validateECLString(Grammar grammar, String txt){
        if(StringUtils.isNotEmpty(txt)){
            Parser parser = new Parser(grammar);
            parser.setInputString(txt);
            try {
                parser.parse();
                return parser.getResult().success();
            } catch (Exception e) {
            	logger.error("Error: " + e);
            }
        }
        return false;
    }    
}
