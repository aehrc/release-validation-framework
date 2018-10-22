package org.ihtsdo.rvf.validation.contanst;

import org.ihtsdo.rvf.validation.StructuralTestRunItem;

/**
 * Created by Tin Le
 * on 6/19/2017.
 */
public class ErrorMessage {
    public  static String getErrorDescription(StructuralTestRunItem item) {
        return item.getActualExpectedValue();
    }
}
