package org.ihtsdo.rvf.jira;

import net.rcarz.jiraclient.ICredentials;
import net.rcarz.jiraclient.JiraClient;
import net.rcarz.jiraclient.JiraException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

public class JiraClientFactoryImpl implements JiraClientFactory{

    private ICredentials credentials;
    private String endPoint;

    public JiraClientFactoryImpl(ICredentials credentials, String endPoint) {
        this.credentials = credentials;
        this.endPoint = endPoint;
    }

    @Override
    public JiraClient getJiraClient() throws JiraException {
        return new JiraClient(this.endPoint, this.credentials);
    }
}
