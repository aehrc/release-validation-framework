package org.ihtsdo.rvf.execution.service.util;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.spi.LoggingEvent;

import java.text.SimpleDateFormat;
import java.util.Date;

public class S3LogAppender extends AppenderSkeleton{

    private String runId;
    private StringBuffer stringBuffer = new StringBuffer();

    public S3LogAppender(String runId) {
        this.runId = runId;
    }

    @Override
    protected void append(LoggingEvent loggingEvent) {
        Object mdcValue = loggingEvent.getMDC("runId");
        if(mdcValue != null && runId.equalsIgnoreCase(mdcValue.toString())) {
            String prefix = "[" + loggingEvent.getLevel() + "] - " 
                    + new SimpleDateFormat("dd/MM/YYYY HH:mm:ss").format(new Date(loggingEvent.getTimeStamp()))
                    + " - " + loggingEvent.getThreadName() + " - ";
            stringBuffer.append(prefix + loggingEvent.getRenderedMessage()+"\n");
        }
    }

    public String getTexts() {
        return stringBuffer.toString();
    }

    @Override
    public void close() {

    }

    @Override
    public boolean requiresLayout() {
        return false;
    }
}
