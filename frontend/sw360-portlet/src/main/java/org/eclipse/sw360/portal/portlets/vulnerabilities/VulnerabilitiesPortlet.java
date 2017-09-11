/*
 * Copyright (c) Bosch Software Innovations GmbH 2016.
 * Part of the SW360 Portal Project.
 *
 * SPDX-License-Identifier: EPL-1.0
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.sw360.portal.portlets.vulnerabilities;

import com.google.common.collect.ImmutableList;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;
import org.eclipse.sw360.datahandler.thrift.components.ComponentService;
import org.eclipse.sw360.datahandler.thrift.components.Release;
import org.eclipse.sw360.datahandler.thrift.users.User;
import org.eclipse.sw360.datahandler.thrift.vulnerabilities.ReleaseVulnerabilityRelation;
import org.eclipse.sw360.datahandler.thrift.vulnerabilities.Vulnerability;
import org.eclipse.sw360.datahandler.thrift.vulnerabilities.VulnerabilityService;
import org.eclipse.sw360.datahandler.thrift.vulnerabilities.VulnerabilityWithReleaseRelations;
import org.eclipse.sw360.portal.common.UsedAsLiferayAction;
import org.eclipse.sw360.portal.portlets.Sw360Portlet;
import org.eclipse.sw360.portal.users.UserCacheHolder;

import javax.portlet.*;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
import static org.eclipse.sw360.datahandler.common.SW360Utils.printName;
import static org.eclipse.sw360.portal.common.PortalConstants.*;

/**
 * Vulnerabilities portlet implementation
 *
 * @author birgit.heydenreich@tngtech.com
 */
public class VulnerabilitiesPortlet extends Sw360Portlet {

    private static final Logger log = Logger.getLogger(VulnerabilitiesPortlet.class);
    private static final String YEAR_MONTH_DAY_REGEX = "\\d\\d\\d\\d-\\d\\d-\\d\\d.*";

    private static final String EXTERNAL_ID = Vulnerability._Fields.EXTERNAL_ID.toString();
    private static final String VULNERABLE_CONFIGURATION = Vulnerability._Fields.VULNERABLE_CONFIGURATION.toString();

    //Helper methods
    private void addVulnerabilityBreadcrumb(RenderRequest request, RenderResponse response, Vulnerability vulnerability) {
        PortletURL url = response.createRenderURL();
        url.setParameter(PAGENAME, PAGENAME_DETAIL);
        url.setParameter(VULNERABILITY_ID, vulnerability.getExternalId());

        addBreadcrumbEntry(request, printName(vulnerability), url);
    }

    @Override
    public void doView(RenderRequest request, RenderResponse response) throws IOException, PortletException {
        String pageName = request.getParameter(PAGENAME);
        if (PAGENAME_DETAIL.equals(pageName)) {
            prepareDetailView(request, response);
            include("/html/vulnerabilities/detail.jsp", request, response);
        } else {
            prepareStandardView(request);
            super.doView(request, response);
        }
    }

    @UsedAsLiferayAction
    public void applyFilters(ActionRequest request, ActionResponse response) throws PortletException, IOException {
        response.setRenderParameter(EXTERNAL_ID, nullToEmpty(request.getParameter(EXTERNAL_ID)));
        response.setRenderParameter(VULNERABLE_CONFIGURATION, nullToEmpty(request.getParameter(VULNERABLE_CONFIGURATION)));
    }

    private void prepareStandardView(RenderRequest request) throws IOException {
        List<Vulnerability> vulnerabilities = getFilteredVulnerabilityList(request);
        shortenTimeStampsToDates(vulnerabilities);
        request.setAttribute(VULNERABILITY_LIST, vulnerabilities);
    }

    private List<Vulnerability> getFilteredVulnerabilityList(PortletRequest request) throws IOException {
        List<Vulnerability> vulnerabilities;

        String externalId = request.getParameter(EXTERNAL_ID);
        String vulnerableConfig = request.getParameter(VULNERABLE_CONFIGURATION);

        try {
            final User user = UserCacheHolder.getUserFromRequest(request);
            VulnerabilityService.Iface vulnerabilityClient = thriftClients.makeVulnerabilityClient();

            if (!isNullOrEmpty(externalId) || !isNullOrEmpty(vulnerableConfig)) {
                vulnerabilities = vulnerabilityClient.getVulnerabilitiesByExternalIdOrConfiguration(externalId, vulnerableConfig, user);
            } else {
                vulnerabilities = vulnerabilityClient.getLatestVulnerabilities(user, 20);
            }
        } catch (TException e) {
            log.error("Could not search components in backend ", e);
            vulnerabilities = Collections.emptyList();
        }
        return vulnerabilities;
    }


    private void shortenTimeStampsToDates(List<Vulnerability> vulnerabilities) {
        vulnerabilities.stream().forEach(v -> {
            if (isFormattedTimeStamp(v.getPublishDate())) {
                v.setPublishDate(getDateFromFormattedTimeStamp(v.getPublishDate()));
            }
            if (isFormattedTimeStamp(v.getLastExternalUpdate())) {
                v.setLastExternalUpdate(getDateFromFormattedTimeStamp(v.getLastExternalUpdate()));
            }
            if (v.isSetCvssTime() && isFormattedTimeStamp(v.getCvssTime())) {
                v.setCvssTime(getDateFromFormattedTimeStamp(v.getCvssTime()));
            }
        });
    }

    private String getDateFromFormattedTimeStamp(String formattedTimeStamp) {
        return formattedTimeStamp.substring(0, 10);
    }

    private boolean isFormattedTimeStamp(String potentialTimestamp) {
        return potentialTimestamp.matches(YEAR_MONTH_DAY_REGEX);
    }

    private void prepareDetailView(RenderRequest request, RenderResponse response) throws IOException, PortletException {
        User user = UserCacheHolder.getUserFromRequest(request);
        String externalId = request.getParameter(VULNERABILITY_ID);
        if (externalId != null) {
            try {
                VulnerabilityService.Iface client = thriftClients.makeVulnerabilityClient();
                VulnerabilityWithReleaseRelations vulnerabilityWithReleaseRelations = client.getVulnerabilityWithReleaseRelationsByExternalId(externalId, user);

                if (vulnerabilityWithReleaseRelations != null) {
                    Vulnerability vulnerability = vulnerabilityWithReleaseRelations.getVulnerability();
                    List<Release> releases = getReleasesFromRelations(user, vulnerabilityWithReleaseRelations);

                    request.setAttribute(VULNERABILITY, vulnerability);
                    request.setAttribute(DOCUMENT_ID, externalId);
                    request.setAttribute(USING_RELEASES, releases);

                    addVulnerabilityBreadcrumb(request, response, vulnerability);
                }

            } catch (TException e) {
                log.error("Error fetching vulnerability from backend!", e);
            }
        }
    }

    private List<Release> getReleasesFromRelations(User user, VulnerabilityWithReleaseRelations vulnerabilityWithReleaseRelations) {
        if (vulnerabilityWithReleaseRelations != null) {
            List<ReleaseVulnerabilityRelation> relations = vulnerabilityWithReleaseRelations.getReleaseRelation();

            Set<String> ids = relations.stream()
                    .map(ReleaseVulnerabilityRelation::getReleaseId)
                    .collect(Collectors.toSet());

            try {
                ComponentService.Iface client = thriftClients.makeComponentClient();
                return client.getReleasesById(ids, user);
            } catch (TException e) {
                log.error("Error fetching releases from backend!", e);
            }
        }
        return ImmutableList.of();
    }
}
