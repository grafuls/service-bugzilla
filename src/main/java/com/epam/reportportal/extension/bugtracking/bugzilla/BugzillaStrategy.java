/*
 * Copyright 2016 EPAM Systems
 * 
 * 
 * This file is part of EPAM Report Portal.
 * https://github.com/reportportal/service-jira
 * 
 * Report Portal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Report Portal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Report Portal.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.epam.reportportal.extension.bugtracking.bugzilla;

import b4j.core.DefaultIssue;
import b4j.core.Issue;
import com.epam.reportportal.extension.bugtracking.ExternalSystemStrategy;
import com.epam.ta.reportportal.config.CacheConfiguration;
import com.epam.ta.reportportal.database.DataStorage;
import com.epam.ta.reportportal.database.entity.ExternalSystem;
import com.epam.ta.reportportal.exception.ReportPortalException;
import com.epam.ta.reportportal.ws.model.ErrorType;
import com.epam.ta.reportportal.ws.model.externalsystem.PostFormField;
import com.epam.ta.reportportal.ws.model.externalsystem.PostTicketRQ;
import com.epam.ta.reportportal.ws.model.externalsystem.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import rs.baselib.security.AuthorizationCallback;
import rs.baselib.security.SimpleAuthorizationCallback;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.epam.ta.reportportal.commons.Predicates.*;
import static com.epam.ta.reportportal.commons.validation.BusinessRule.expect;
import static com.epam.ta.reportportal.commons.validation.Suppliers.formattedSupplier;
import static com.epam.ta.reportportal.ws.model.ErrorType.UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM;

/**
 * Bugzilla related implementation of {@link ExternalSystemStrategy}.
 *
 * @author Andrei Varabyeu
 * @author Andrei_Ramanchuk
 */

public class BugzillaStrategy implements ExternalSystemStrategy {

    private static final String BUG = "Bug";
    private static final Logger LOGGER = LoggerFactory.getLogger(BugzillaStrategy.class);

    @Autowired
    private DataStorage dataStorage;

    @Autowired
    private BZTicketDescriptionService descriptionService;

    @Override
    public boolean checkConnection(ExternalSystem details){
        validateExternalSystemDetails(details);
        try (BugzillaSession session = getBugzillaSession(details.getUrl(),details.getUsername(),details.getPassword()))
        {
            return session.open();
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            return false;
        }
    }

    private Issue getIssue(String id, BugzillaSession session){
        Issue issue = session.getIssue(id);
        return issue;
    }

    @Override
    @Cacheable(value = CacheConfiguration.EXTERNAL_SYSTEM_TICKET_CACHE, key = "#system.url + #system.project + #id")
    public Optional<Ticket> getTicket(String id, ExternalSystem details) {
        try (BugzillaSession session = getBugzillaSession(details.getUrl(),details.getUsername(),details.getPassword()))
        {
                Optional<Ticket> ticket = getTicket(id, details, session);
                return ticket;
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            return Optional.empty();
        }
    }

    private Optional<Ticket> getTicket(String id, ExternalSystem details, BugzillaSession session) {
        Issue issue = getIssue(id, session);
        if (issue!=null) {
            return Optional.of(BZTicketUtils.toTicket(issue, details));
        }else{
            return Optional.empty();
        }
    }

    @Override
    public Ticket submitTicket(final PostTicketRQ ticketRQ, ExternalSystem details) {
        expect(ticketRQ.getFields(), not(isNull()))
                .verify(UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM, "External System fields set is empty!");
        List<PostFormField> fields = ticketRQ.getFields();

        // TODO add validation of any field with allowedValues() array
        // Additional validation required for unsupported
        // ticket type and/or components in JIRA.
        PostFormField issuetype = new PostFormField();
        PostFormField components = new PostFormField();
        for (PostFormField object : fields) {
            if ("issuetype".equalsIgnoreCase(object.getId()))
                issuetype = object;
            if ("components".equalsIgnoreCase(object.getId()))
                components = object;
        }

        expect(issuetype.getValue().size(), equalTo(1)).verify(UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM,
                formattedSupplier("[IssueType] field has multiple values '{}' but should be only one",
                        issuetype.getValue()));
        final String issueTypeStr = issuetype.getValue().get(0);

        try (BugzillaSession session = getBugzillaSession(details.getUrl(),details.getUsername(),details.getPassword()))
        {
//            Project jiraProject = getProject(client, details);
//
//            if (null != components.getValue()) {
//                Set<String> validComponents = StreamSupport.stream(jiraProject.getComponents().spliterator(), false)
//                        .map(JiraPredicates.COMPONENT_NAMES).collect(toSet());
//                validComponents.forEach(component -> expect(component, in(validComponents))
//                        .verify(UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM,
//                                formattedSupplier("Component '{}' not exists in the external system", component)));
//            }
//
//            // TODO consider to modify code below - project cached
//            Optional<IssueType> issueType = StreamSupport.stream(jiraProject.getIssueTypes().spliterator(), false)
//                    .filter(input -> issueTypeStr.equalsIgnoreCase(input.getName())).findFirst();
//
//            expect(issueType, Preconditions.IS_PRESENT).verify(UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM,
//                    formattedSupplier("Unable post issue with type '{}' for project '{}'.", issuetype.getValue().get(0),
//                            details.getProject()));
//            IssueInput issueInput = JIRATicketUtils
//                    .toIssueInput(client, jiraProject, issueType, ticketRQ, ticketRQ.getBackLinks().keySet(),
//                            descriptionService);
//
//            Map<String, String> binaryData = findBinaryData(issueInput);
//
//			/*
//             * Claim because we wanna be sure everything is OK
//			 */
//            BasicIssue createdIssue = client.getIssueClient().createIssue(issueInput).claim();
//
//            // post binary data
//            Issue issue = client.getIssueClient().getIssue(createdIssue.getKey()).claim();
//
//            AttachmentInput[] attachmentInputs = new AttachmentInput[binaryData.size()];
//            int counter = 0;
//            for (Map.Entry<String, String> binaryDataEntry : binaryData.entrySet()) {
//                BinaryData data = dataStorage.fetchData(binaryDataEntry.getKey());
//                if (null != data) {
//                    attachmentInputs[counter] = new AttachmentInput(binaryDataEntry.getValue(), data.getInputStream());
//                    counter++;
//                }
//            }
//            if (counter != 0)
//                client.getIssueClient()
//                        .addAttachments(issue.getAttachmentsUri(), Arrays.copyOf(attachmentInputs, counter));
            BugzillaSession session = getBugzillaSession(details.getUrl(), details.getUsername(), details.getPassword());
            Ticket ticket = getTicket("123", details,session).orElse(null);
            session.close();
            return ticket;

        } catch (ReportPortalException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            throw new ReportPortalException(ErrorType.UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM, e.getMessage());
        }
    }

//    /**
//     * Parse ticket description and find binary data
//     *
//     * @param issueInput Jira issue
//     * @return Parsed parameters
//     */
//    private Map<String, String> findBinaryData(IssueInput issueInput) {
//        Map<String, String> binary = new HashMap<>();
//        String description = issueInput.getField(IssueFieldId.DESCRIPTION_FIELD.id).getValue().toString();
//        if (null != description) {
//            // !54086a2c3c0c7d4446beb3e6.jpg| or [^54086a2c3c0c7d4446beb3e6.xml]
//            String regex = "(!|\\[\\^).{24}.{0,5}(\\||\\])";
//            Matcher matcher = Pattern.compile(regex).matcher(description);
//            while (matcher.find()) {
//                String rawValue = description.subSequence(matcher.start(), matcher.end()).toString();
//                String binaryDataName = rawValue.replace("!", "").replace("[", "").replace("]", "").replace("^", "")
//                        .replace("|", "");
//                String binaryDataId = binaryDataName.split("\\.")[0];
//                binary.put(binaryDataId, binaryDataName);
//            }
//        }
//        return binary;
//        return new HashMap<>();
//    }

    @Override
    public List<PostFormField> getTicketFields(final String ticketType, ExternalSystem details) {
//        List<PostFormField> result = new ArrayList<>();
//        try (JiraRestClient client = getClient(details.getUrl(), details.getUsername(),
//                simpleEncryptor.decrypt(details.getPassword()))) {
//            Project jiraProject = getProject(client, details);
//            Optional<IssueType> issueType = StreamSupport.stream(jiraProject.getIssueTypes().spliterator(), false)
//                    .filter(input -> ticketType.equalsIgnoreCase(input.getName())).findFirst();
//
//            BusinessRule.expect(issueType, Preconditions.IS_PRESENT)
//                    .verify(ErrorType.UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM,
//                            "Ticket type '" + ticketType + "' not found");
//
//            GetCreateIssueMetadataOptions options = new GetCreateIssueMetadataOptionsBuilder()
//                    .withExpandedIssueTypesFields()
//                    .withProjectKeys(jiraProject.getKey()).build();
//            Iterable<CimProject> projects = client.getIssueClient().getCreateIssueMetadata(options).claim();
//            CimProject project = projects.iterator().next();
//            CimIssueType cimIssueType = EntityHelper.findEntityById(project.getIssueTypes(), issueType.get().getId());
//            for (String key : cimIssueType.getFields().keySet()) {
//                List<String> defValue = null;
//                CimFieldInfo issueField = cimIssueType.getFields().get(key);
//                // Field ID for next JIRA POST ticket requests
//                String fieldID = issueField.getId();
//                String fieldType = issueField.getSchema().getType();
//                List<AllowedValue> allowed = new ArrayList<>();
//                // Field NAME for user friendly UI output (for UI only)
//                String fieldName = issueField.getName();
//
//                if (fieldID.equalsIgnoreCase(IssueFieldId.COMPONENTS_FIELD.id)) {
//                    for (BasicComponent component : jiraProject.getComponents()) {
//                        allowed.add(new AllowedValue(String.valueOf(component.getId()), component.getName()));
//                    }
//                }
//                if (fieldID.equalsIgnoreCase(IssueFieldId.FIX_VERSIONS_FIELD.id)) {
//                    for (Version version : jiraProject.getVersions()) {
//                        allowed.add(new AllowedValue(String.valueOf(version.getId()), version.getName()));
//                    }
//                }
//                if (fieldID.equalsIgnoreCase(IssueFieldId.AFFECTS_VERSIONS_FIELD.id)) {
//                    for (Version version : jiraProject.getVersions()) {
//                        allowed.add(new AllowedValue(String.valueOf(version.getId()), version.getName()));
//                    }
//                }
//                if (fieldID.equalsIgnoreCase(IssueFieldId.PRIORITY_FIELD.id)) {
//                    if (null != cimIssueType.getField(IssueFieldId.PRIORITY_FIELD)) {
//                        Iterable<Object> allowedValuesForPriority = cimIssueType.getField(IssueFieldId.PRIORITY_FIELD)
//                                .getAllowedValues();
//                        for (Object singlePriority : allowedValuesForPriority) {
//                            BasicPriority priority = (BasicPriority) singlePriority;
//                            allowed.add(new AllowedValue(String.valueOf(priority.getId()), priority.getName()));
//                        }
//                    }
//                }
//                if (fieldID.equalsIgnoreCase(IssueFieldId.ISSUE_TYPE_FIELD.id)) {
//                    defValue = Collections.singletonList(ticketType);
//                }
//                if (fieldID.equalsIgnoreCase(IssueFieldId.ASSIGNEE_FIELD.id)) {
//                    allowed = getJiraProjectAssignee(jiraProject);
//                }
//
//                //@formatter:off
//                // Skip project field as external from list
//                // Skip attachment cause we are not providing this functionality now
//                // Skip timetracking field cause complexity. There are two fields with Original Estimation and Remaining Estimation.
//                // Skip Story Link as greenhopper plugin field.
//                // Skip Sprint field as complex one.
//                //@formatter:on
//                if ("project".equalsIgnoreCase(fieldID) || "attachment".equalsIgnoreCase(fieldID)
//                        || "timetracking".equalsIgnoreCase(fieldID) || "Epic Link".equalsIgnoreCase(fieldName)
//                        || "Sprint".equalsIgnoreCase(fieldName))
//                    continue;
//
//                result.add(
//                        new PostFormField(fieldID, fieldName, fieldType, issueField.isRequired(), defValue, allowed));
//            }
//            return result;
//        } catch (Exception e) {
//            LOGGER.error(e.getMessage(), e);
//            return new ArrayList<>();
//        }
//
        return new ArrayList<>();
    }

    @Override
    public List<String> getIssueTypes(ExternalSystem system) {
//        try (JiraRestClient client = getClient(system.getUrl(), system.getUsername(),
//                simpleEncryptor.decrypt(system.getPassword()))) {
//            Project jiraProject = getProject(client, system);
//            return StreamSupport.stream(jiraProject.getIssueTypes().spliterator(), false)
//                    .map(IssueType::getName).collect(Collectors.toList());
//        } catch (Exception e) {
//            LOGGER.error(e.getMessage(), e);
//            return Collections.emptyList();
//        }
        return Collections.emptyList();
    }

    /**
     * JIRA properties validator
     *
     * @param details External system details
     */
    private void validateExternalSystemDetails(ExternalSystem details) {
//        if (details.getExternalSystemAuth().equals(AuthType.BASIC)) {
//            expect(details.getUsername(), notNull())
//                    .verify(UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM, "Username value cannot be NULL");
//            expect(details.getPassword(), notNull())
//                    .verify(UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM, "Password value cannot be NULL");
//        } else if (details.getExternalSystemAuth().equals(AuthType.OAUTH)) {
//            expect(details.getAccessKey(), notNull())
//                    .verify(UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM, "AccessKey value cannot be NULL");
//        }
//        expect(details.getProject(), notNull())
//                .verify(UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM, "JIRA project value cannot be NULL");
//        expect(details.getUrl(), notNull())
//                .verify(UNABLE_INTERACT_WITH_EXTRERNAL_SYSTEM, "JIRA URL value cannot be NULL");
    }

//    /**
//     * Get list of project users available for assignee field
//     *
//     * @param jiraProject Project from JIRA
//     * @return List of allowed values
//     */
//    private List<AllowedValue> getJiraProjectAssignee(Project jiraProject) {
//        Iterable<BasicProjectRole> jiraProjectRoles = jiraProject.getProjectRoles();
//        try {
//            return StreamSupport.stream(jiraProjectRoles.spliterator(), false)
//                    .filter(role -> role instanceof ProjectRole)
//                    .map(role -> (ProjectRole) role)
//                    .flatMap(role -> StreamSupport.stream(role.getActors().spliterator(), false))
//                    .distinct()
//                    .map(actor -> new AllowedValue(String.valueOf(actor.getId()), actor.getDisplayName()))
//                    .collect(Collectors.toList());
//        } catch (Exception e) {
//            LOGGER.error(e.getMessage(), e);
//            throw new ReportPortalException("There is a problem while getting issue types", e);
//        }
//
//    }

    public BugzillaSession getBugzillaSession(String uri, String username, String password) {
        BugzillaSession session = new BugzillaSession();
        try {
            session.setBaseUrl(new URL(uri));
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        session.setBugzillaBugClass(DefaultIssue.class);

        AuthorizationCallback authorizationCallback = new SimpleAuthorizationCallback( username, password );
        session.getHttpSessionParams().setAuthorizationCallback(authorizationCallback);

        return session;
    }
}
