package org.ihtsdo.rvf.service;

import org.ihtsdo.rvf.entity.Assertion;
import org.ihtsdo.rvf.entity.AssertionGroup;
import org.ihtsdo.rvf.model.AssertionGroupConfiguration;

import java.io.IOException;
import java.util.Set;

public interface ExternalAssertionGroupService {

    AssertionGroupConfiguration loadConfigurationsByAssertionGroupName(String groupName) throws IOException;

    AssertionGroup findByName(String groupName);

    Set<Assertion> loadAssertionsByAssertionGroupName(String groupName);

}
