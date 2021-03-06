/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.adapter.servlet;

import static javax.ws.rs.core.Response.Status.OK;
import static org.hamcrest.Matchers.*;
import static org.keycloak.OAuth2Constants.PASSWORD;
import static org.keycloak.testsuite.admin.ApiUtil.createUserAndResetPasswordWithAdminClient;
import static org.keycloak.testsuite.admin.Users.setPasswordFor;
import static org.keycloak.testsuite.AbstractAuthTest.createUserRepresentation;
import static org.keycloak.testsuite.adapter.AbstractServletsAdapterTest.samlServletDeployment;
import static org.keycloak.testsuite.auth.page.AuthRealm.SAMLSERVLETDEMO;
import static org.keycloak.testsuite.saml.AbstractSamlTest.REALM_PRIVATE_KEY;
import static org.keycloak.testsuite.saml.AbstractSamlTest.REALM_PUBLIC_KEY;
import static org.keycloak.testsuite.util.Matchers.bodyHC;
import static org.keycloak.testsuite.util.Matchers.statusCodeIsHC;
import static org.keycloak.testsuite.util.URLAssert.assertCurrentUrlStartsWith;
import static org.keycloak.testsuite.util.WaitUtils.waitForPageToLoad;
import static org.keycloak.testsuite.util.WaitUtils.waitUntilElement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URL;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.SOAPHeader;
import javax.xml.soap.SOAPHeaderElement;
import javax.xml.soap.SOAPMessage;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.graphene.page.Page;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import org.junit.Assert;
import org.junit.Test;

import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ProtocolMappersResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.common.util.Base64;
import org.keycloak.common.util.KeyUtils;
import org.keycloak.common.util.PemUtils;
import org.keycloak.dom.saml.v2.protocol.AuthnRequestType;
import org.keycloak.dom.saml.v2.protocol.ResponseType;
import org.keycloak.dom.saml.v2.protocol.StatusCodeType;
import org.keycloak.dom.saml.v2.protocol.StatusResponseType;
import org.keycloak.keys.Attributes;
import org.keycloak.keys.ImportedRsaKeyProviderFactory;
import org.keycloak.keys.KeyProvider;
import org.keycloak.protocol.saml.SamlConfigAttributes;
import org.keycloak.protocol.saml.SamlProtocol;
import org.keycloak.protocol.saml.mappers.AttributeStatementHelper;
import org.keycloak.protocol.saml.mappers.RoleListMapper;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.saml.SAML2ErrorResponseBuilder;
import org.keycloak.saml.common.constants.JBossSAMLConstants;
import org.keycloak.saml.common.constants.JBossSAMLURIConstants;
import org.keycloak.saml.common.util.DocumentUtil;
import org.keycloak.saml.common.util.XmlKeyInfoKeyNameTransformer;
import org.keycloak.saml.processing.core.parsers.saml.SAMLParser;
import org.keycloak.saml.processing.core.saml.v2.common.SAMLDocumentHolder;
import org.keycloak.services.resources.RealmsResource;
import org.keycloak.testsuite.adapter.AbstractServletsAdapterTest;
import org.keycloak.testsuite.adapter.page.*;
import org.keycloak.testsuite.admin.ApiUtil;
import org.keycloak.testsuite.arquillian.annotation.AppServerContainer;
import org.keycloak.testsuite.arquillian.containers.ContainerConstants;
import org.keycloak.testsuite.auth.page.login.Login;
import org.keycloak.testsuite.auth.page.login.SAMLIDPInitiatedLogin;
import org.keycloak.testsuite.auth.page.login.SAMLPostLoginTenant1;
import org.keycloak.testsuite.auth.page.login.SAMLPostLoginTenant2;
import org.keycloak.testsuite.page.AbstractPage;
import org.keycloak.testsuite.saml.AbstractSamlTest;
import org.keycloak.testsuite.updaters.ClientAttributeUpdater;
import org.keycloak.testsuite.util.ProtocolMapperUtil;
import org.keycloak.testsuite.util.SamlClient;
import org.keycloak.testsuite.util.SamlClient.Binding;
import org.keycloak.testsuite.util.SamlClientBuilder;
import org.keycloak.testsuite.util.UserBuilder;
import org.keycloak.testsuite.util.WaitUtils;
import org.keycloak.testsuite.utils.io.IOUtil;

import org.openqa.selenium.By;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.xml.sax.SAXException;

/**
 * @author mhajas
 */
@AppServerContainer(ContainerConstants.APP_SERVER_UNDERTOW)
@AppServerContainer(ContainerConstants.APP_SERVER_WILDFLY)
@AppServerContainer(ContainerConstants.APP_SERVER_WILDFLY_DEPRECATED)
@AppServerContainer(ContainerConstants.APP_SERVER_EAP)
@AppServerContainer(ContainerConstants.APP_SERVER_EAP6)
@AppServerContainer(ContainerConstants.APP_SERVER_EAP71)
public class SAMLServletAdapterTest extends AbstractServletsAdapterTest {
    @Page
    protected BadClientSalesPostSigServlet badClientSalesPostSigServletPage;

    @Page
    protected BadRealmSalesPostSigServlet badRealmSalesPostSigServletPage;

    @Page
    protected EmployeeAcsServlet employeeAcsServletPage;

    @Page
    protected Employee2Servlet employee2ServletPage;

    @Page
    protected EmployeeSigServlet employeeSigServletPage;

    @Page
    protected EmployeeSigPostNoIdpKeyServlet employeeSigPostNoIdpKeyServletPage;

    @Page
    protected EmployeeSigRedirNoIdpKeyServlet employeeSigRedirNoIdpKeyServletPage;

    @Page
    protected EmployeeSigRedirOptNoIdpKeyServlet employeeSigRedirOptNoIdpKeyServletPage;

    @Page
    protected EmployeeSigFrontServlet employeeSigFrontServletPage;

    @Page
    protected SalesMetadataServlet salesMetadataServletPage;

    @Page
    protected SalesPostServlet salesPostServletPage;

    @Page
    private SalesPost2Servlet salesPost2ServletPage;

    @Page
    protected SalesPostEncServlet salesPostEncServletPage;

    @Page
    protected SalesPostEncSignAssertionsOnlyServlet salesPostEncSignAssertionsOnlyServletPage;

    @Page
    protected SalesPostPassiveServlet salesPostPassiveServletPage;

    @Page
    protected SalesPostSigServlet salesPostSigServletPage;

    @Page
    protected SalesPostSigEmailServlet salesPostSigEmailServletPage;

    @Page
    protected SalesPostSigPersistentServlet salesPostSigPersistentServletPage;

    @Page
    protected SalesPostSigTransientServlet salesPostSigTransientServletPage;

    @Page
    protected SAMLIDPInitiatedLogin samlidpInitiatedLogin;

    protected boolean forbiddenIfNotAuthenticated = true;

    @Page
    protected SalesPostAssertionAndResponseSig salesPostAssertionAndResponseSigPage;

    @Page
    protected BadAssertionSalesPostSig badAssertionSalesPostSigPage;

    @Page
    protected MissingAssertionSig missingAssertionSigPage;

    @Page
    protected EmployeeServlet employeeServletPage;

    @Page
    protected DifferentCookieNameServlet differentCookieNameServletPage;

    @Page
    private InputPortal inputPortalPage;

    @Page
    private SAMLIDPInitiatedLogin samlidpInitiatedLoginPage;

    @Page
    protected SalesPostAutodetectServlet salesPostAutodetectServletPage;

    @Page
    protected AdapterLogoutPage adapterLogoutPage;

    @Page
    protected EcpSP ecpSPPage;

    @Page
    protected MultiTenant1Saml multiTenant1SamlPage;
    
    @Page
    protected MultiTenant2Saml multiTenant2SamlPage;
    
    @Page
    protected SAMLPostLoginTenant1 tenant1RealmSAMLPostLoginPage;
    
    @Page
    protected SAMLPostLoginTenant2 tenant2RealmSAMLPostLoginPage;

    public static final String FORBIDDEN_TEXT = "HTTP status code: 403";
    public static final String WEBSPHERE_FORBIDDEN_TEXT = "Error reported: 403";

    @Deployment(name = BadClientSalesPostSigServlet.DEPLOYMENT_NAME)
    protected static WebArchive badClientSalesPostSig() {
        return samlServletDeployment(BadClientSalesPostSigServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = BadRealmSalesPostSigServlet.DEPLOYMENT_NAME)
    protected static WebArchive badRealmSalesPostSig() {
        return samlServletDeployment(BadRealmSalesPostSigServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = EmployeeAcsServlet.DEPLOYMENT_NAME)
    protected static WebArchive employeeAssertionConsumerServiceUrlSet() {
        return samlServletDeployment(EmployeeAcsServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = Employee2Servlet.DEPLOYMENT_NAME)
    protected static WebArchive employee2() {
        return samlServletDeployment(Employee2Servlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = EmployeeSigServlet.DEPLOYMENT_NAME)
    protected static WebArchive employeeSig() {
        return samlServletDeployment(EmployeeSigServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = EmployeeSigPostNoIdpKeyServlet.DEPLOYMENT_NAME)
    protected static WebArchive employeeSigPostNoIdpKeyServlet() {
        return samlServletDeployment(EmployeeSigPostNoIdpKeyServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = EmployeeSigRedirNoIdpKeyServlet.DEPLOYMENT_NAME)
    protected static WebArchive employeeSigRedirNoIdpKeyServlet() {
        return samlServletDeployment(EmployeeSigRedirNoIdpKeyServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = EmployeeSigRedirOptNoIdpKeyServlet.DEPLOYMENT_NAME)
    protected static WebArchive employeeSigRedirOptNoIdpKeyServlet() {
        return samlServletDeployment(EmployeeSigRedirOptNoIdpKeyServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = EmployeeSigFrontServlet.DEPLOYMENT_NAME)
    protected static WebArchive employeeSigFront() {
        return samlServletDeployment(EmployeeSigFrontServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = SalesMetadataServlet.DEPLOYMENT_NAME)
    protected static WebArchive salesMetadata() {
        return samlServletDeployment(SalesMetadataServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = SalesPostServlet.DEPLOYMENT_NAME)
    protected static WebArchive salesPost() {
        return samlServletDeployment(SalesPostServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = SalesPostEncServlet.DEPLOYMENT_NAME)
    protected static WebArchive salesPostEnc() {
        return samlServletDeployment(SalesPostEncServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = SalesPostEncSignAssertionsOnlyServlet.DEPLOYMENT_NAME)
    protected static WebArchive salesPostEncSignAssertionsOnly() {
        return samlServletDeployment(SalesPostEncSignAssertionsOnlyServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = SalesPostPassiveServlet.DEPLOYMENT_NAME)
    protected static WebArchive salesPostPassive() {
        return samlServletDeployment(SalesPostPassiveServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = SalesPostSigServlet.DEPLOYMENT_NAME)
    protected static WebArchive salesPostSig() {
        return samlServletDeployment(SalesPostSigServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = SalesPostSigEmailServlet.DEPLOYMENT_NAME)
    protected static WebArchive salesPostSigEmail() {
        return samlServletDeployment(SalesPostSigEmailServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = SalesPostSigPersistentServlet.DEPLOYMENT_NAME)
    protected static WebArchive salesPostSigPersistent() {
        return samlServletDeployment(SalesPostSigPersistentServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = SalesPostSigTransientServlet.DEPLOYMENT_NAME)
    protected static WebArchive salesPostSigTransient() {
        return samlServletDeployment(SalesPostSigTransientServlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = InputPortal.DEPLOYMENT_NAME)
    protected static WebArchive inputPortal() {
        return samlServletDeployment(InputPortal.DEPLOYMENT_NAME, "input-portal/WEB-INF/web.xml" , InputServlet.class, ServletTestUtils.class);
    }

    @Deployment(name = SalesPost2Servlet.DEPLOYMENT_NAME)
    protected static WebArchive salesPost2() {
        return samlServletDeployment(SalesPost2Servlet.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = DifferentCookieNameServlet.DEPLOYMENT_NAME)
    protected static WebArchive differentCokieName() {
        return samlServletDeployment(DifferentCookieNameServlet.DEPLOYMENT_NAME, "different-cookie-name/WEB-INF/web.xml", SendUsernameServlet.class);
    }

    @Deployment(name = SalesPostAssertionAndResponseSig.DEPLOYMENT_NAME)
    protected static WebArchive salesPostAssertionAndResponseSig() {
        return samlServletDeployment(SalesPostAssertionAndResponseSig.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = BadAssertionSalesPostSig.DEPLOYMENT_NAME)
    protected static WebArchive badAssertionSalesPostSig() {
        return samlServletDeployment(BadAssertionSalesPostSig.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = MissingAssertionSig.DEPLOYMENT_NAME)
    protected static WebArchive missingAssertionSig() {
        return samlServletDeployment(MissingAssertionSig.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = EmployeeServlet.DEPLOYMENT_NAME)
    protected static WebArchive employeeServlet() {
        return samlServletDeployment(EmployeeServlet.DEPLOYMENT_NAME, "employee/WEB-INF/web.xml", SamlSPFacade.class, ServletTestUtils.class)
          .add(new StringAsset("<html><body>Logged out</body></html>"), "/logout.jsp");
    }

    @Deployment(name = AdapterLogoutPage.DEPLOYMENT_NAME)
    protected static WebArchive logoutWar() {
        return AdapterLogoutPage.createDeployment();
    }

    @Deployment(name = SalesPostAutodetectServlet.DEPLOYMENT_NAME)
    protected static WebArchive salesPostAutodetect() {
        return samlServletDeployment(SalesPostAutodetectServlet.DEPLOYMENT_NAME, "sales-post-autodetect/WEB-INF/web.xml", SendUsernameServlet.class);
    }

    @Deployment(name = EcpSP.DEPLOYMENT_NAME)
    protected static WebArchive ecpSp() {
        return samlServletDeployment(EcpSP.DEPLOYMENT_NAME, SendUsernameServlet.class);
    }

    @Deployment(name = MultiTenant1Saml.DEPLOYMENT_NAME)
    protected static WebArchive multiTenant() {
        return samlServletDeploymentMultiTenant(MultiTenant1Saml.DEPLOYMENT_NAME, "multi-tenant-saml/WEB-INF/web.xml", 
                "tenant1-keycloak-saml.xml", "tenant2-keycloak-saml.xml",
                "keystore-tenant1.jks", "keystore-tenant2.jks", 
                SendUsernameServlet.class, SamlMultiTenantResolver.class);
    }

    @Override
    public void addAdapterTestRealms(List<RealmRepresentation> testRealms) {
        testRealms.add(IOUtil.loadRealm("/adapter-test/keycloak-saml/testsaml.json"));
        testRealms.add(IOUtil.loadRealm("/adapter-test/keycloak-saml/tenant1-realm.json"));
        testRealms.add(IOUtil.loadRealm("/adapter-test/keycloak-saml/tenant2-realm.json"));
    }

    @Override
    public void setDefaultPageUriParameters() {
        super.setDefaultPageUriParameters();
        testRealmPage.setAuthRealm(SAMLSERVLETDEMO);
        testRealmSAMLRedirectLoginPage.setAuthRealm(SAMLSERVLETDEMO);
        testRealmSAMLPostLoginPage.setAuthRealm(SAMLSERVLETDEMO);
    }

    private void assertForbidden(AbstractPage page, String expectedNotContains) {
        page.navigateTo();
        waitUntilElement(By.xpath("//body")).text().not().contains(expectedNotContains);
        //Different 403 status page on EAP and Wildfly
        Assert.assertTrue(driver.getPageSource().contains("Forbidden")
                || driver.getPageSource().contains(FORBIDDEN_TEXT)
                || driver.getPageSource().contains(WEBSPHERE_FORBIDDEN_TEXT)); // WebSphere
    }

    private void assertSuccessfullyLoggedIn(AbstractPage page, String expectedText) {
        page.navigateTo();
        waitUntilElement(By.xpath("//body")).text().contains(expectedText);
    }

    private void assertForbiddenLogin(AbstractPage page, String username, String password, Login loginPage, String expectedNotContains) {
        page.navigateTo();
        assertCurrentUrlStartsWith(loginPage);
        loginPage.form().login(username, password);
        waitUntilElement(By.xpath("//body")).text().not().contains(expectedNotContains);
        //Different 403 status page on EAP and Wildfly
        Assert.assertTrue(driver.getPageSource().contains("Forbidden")
                || driver.getPageSource().contains(FORBIDDEN_TEXT)
                || driver.getPageSource().contains(WEBSPHERE_FORBIDDEN_TEXT)); // WebSphere
    }
    
    private void assertFailedLogin(AbstractPage page, UserRepresentation user, Login loginPage) {
        page.navigateTo();
        assertCurrentUrlStartsWith(loginPage);
        loginPage.form().login(user);
        // we remain in login
        assertCurrentUrlStartsWith(loginPage);
    }

    private void assertSuccessfulLogin(AbstractPage page, UserRepresentation user, Login loginPage, String expectedString) {
        page.navigateTo();
        assertCurrentUrlStartsWith(loginPage);
        loginPage.form().login(user);
        waitUntilElement(By.xpath("//body")).text().contains(expectedString);
    }

    private void testSuccessfulAndUnauthorizedLogin(SAMLServlet page, Login loginPage) {
        testSuccessfulAndUnauthorizedLogin(page, loginPage, "principal=bburke");
    }

    private void testSuccessfulAndUnauthorizedLogin(SAMLServlet page, Login loginPage, String expectedText) {
        testSuccessfulAndUnauthorizedLogin(page, loginPage, expectedText, "principal=");
    }

    private void testSuccessfulAndUnauthorizedLogin(SAMLServlet page, Login loginPage, String expectedText, String expectedNotContains) {
        assertSuccessfulLogin(page, bburkeUser, loginPage, expectedText);
        page.logout();
        checkLoggedOut(page, loginPage);
        assertForbiddenLogin(page, "unauthorized", "password", loginPage, expectedNotContains);
        page.logout();
        checkLoggedOut(page, loginPage);
    }

    private void checkLoggedOut(AbstractPage page, Login loginPage) {
        page.navigateTo();
        waitForPageToLoad();
        assertCurrentUrlStartsWith(loginPage);
    }

    @Test
    public void disabledClientTest() {
        ClientResource clientResource = ApiUtil.findClientResourceByClientId(testRealmResource(), AbstractSamlTest.SAML_CLIENT_ID_SALES_POST_SIG);
        ClientRepresentation client = clientResource.toRepresentation();
        client.setEnabled(false);
        clientResource.update(client);

        salesPostSigServletPage.navigateTo();
        waitUntilElement(By.xpath("//body")).text().contains("Login requester not enabled");

        client.setEnabled(true);
        clientResource.update(client);
    }

    @Test
    public void unauthorizedSSOTest() {
        assertForbiddenLogin(salesPostServletPage, "unauthorized", "password", testRealmSAMLPostLoginPage, "principal=");
        assertForbidden(employee2ServletPage, "principal=");
        assertForbidden(employeeSigFrontServletPage, "principal=");
        assertForbidden(salesPostSigPersistentServletPage, "principal=");
        salesPostServletPage.logout();
        checkLoggedOut(salesPostServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void singleLoginAndLogoutSAMLTest() {
        assertSuccessfulLogin(salesPostServletPage, bburkeUser, testRealmSAMLPostLoginPage, "principal=bburke");
        assertSuccessfullyLoggedIn(salesPostSigServletPage, "principal=bburke");
        assertSuccessfullyLoggedIn(employee2ServletPage, "principal=bburke");
        assertSuccessfullyLoggedIn(salesPostEncServletPage, "principal=bburke");

        employeeSigFrontServletPage.logout();

        checkLoggedOut(employeeSigFrontServletPage, testRealmSAMLRedirectLoginPage);
        checkLoggedOut(employeeSigServletPage, testRealmSAMLRedirectLoginPage);

        salesPostPassiveServletPage.navigateTo();
        if (forbiddenIfNotAuthenticated) {
            assertOnForbiddenPage();
        } else {
            waitUntilElement(By.xpath("//body")).text().contains("principal=null");
        }

        checkLoggedOut(salesPostSigEmailServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void badClientSalesPostSigTest() {
        badClientSalesPostSigServletPage.navigateTo();
        waitUntilElement(By.xpath("//body")).text().contains("Invalid requester");
    }

    @Test
    public void badRealmSalesPostSigTest() {
        badRealmSalesPostSigServletPage.navigateTo();
        testRealmSAMLRedirectLoginPage.form().login(bburkeUser);

        waitUntilElement(By.xpath("//body")).text().not().contains("principal=");
        //Different 403 status page on EAP and Wildfly
        Assert.assertTrue(driver.getPageSource().contains("Forbidden")
                || driver.getPageSource().contains(FORBIDDEN_TEXT)
                || driver.getPageSource().contains(WEBSPHERE_FORBIDDEN_TEXT)); // WebSphere
    }

    @Test
    public void employee2Test() {
        testSuccessfulAndUnauthorizedLogin(employee2ServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void employeeSigTest() {
        testSuccessfulAndUnauthorizedLogin(employeeSigServletPage, testRealmSAMLRedirectLoginPage);
    }

    @Test
    public void employeeAcsTest() {
        SAMLDocumentHolder samlResponse = new SamlClientBuilder()
          .navigateTo(employeeAcsServletPage.buildUri())
          .getSamlResponse(Binding.POST);

        Assert.assertThat(samlResponse.getSamlObject(), instanceOf(AuthnRequestType.class));
        Assert.assertThat(((AuthnRequestType) samlResponse.getSamlObject()).getAssertionConsumerServiceURL(), notNullValue());
        Assert.assertThat(((AuthnRequestType) samlResponse.getSamlObject()).getAssertionConsumerServiceURL().getPath(), is("/employee-acs/a/different/endpoint/for/saml"));

        assertSuccessfulLogin(employeeAcsServletPage, bburkeUser, testRealmSAMLPostLoginPage, "principal=bburke");
    }

    @Test
    public void multiTenant1SamlTest() throws Exception {
        multiTenant1SamlPage.setRolesToCheck("user");

        try {
            UserRepresentation user1 = createUserRepresentation("user-tenant1", "user-tenant1@redhat.com", "Bill", "Burke", true);
            setPasswordFor(user1, "user-tenant1");
            // check the user in the tenant logs in ok
            assertSuccessfulLogin(multiTenant1SamlPage, user1, tenant1RealmSAMLPostLoginPage, "principal=user-tenant1");
            // check the issuer is the correct tenant
            driver.navigate().to(multiTenant1SamlPage.getUriBuilder().clone().path("getAssertionIssuer").build().toASCIIString());
            waitUntilElement(By.xpath("//body")).text().contains("/auth/realms/tenant1");
            // check logout
            multiTenant1SamlPage.logout();
            checkLoggedOut(multiTenant1SamlPage, tenant1RealmSAMLPostLoginPage);
            // check a user in the other tenant doesn't login
            UserRepresentation user2 = createUserRepresentation("user-tenant2", "user-tenant2@redhat.com", "Bill", "Burke", true);
            setPasswordFor(user2, "user-tenant2");
            assertFailedLogin(multiTenant1SamlPage, user2, tenant1RealmSAMLPostLoginPage);
        } finally {
            multiTenant1SamlPage.checkRolesEndPoint(false);
        }
    }

    @Test
    public void multiTenant2SamlTest() throws Exception {
        multiTenant2SamlPage.setRolesToCheck("user");

        try {
            UserRepresentation user2 = createUserRepresentation("user-tenant2", "user-tenant2@redhat.com", "Bill", "Burke", true);
            setPasswordFor(user2, "user-tenant2");
            // check the user in the tenant logs in ok
            assertSuccessfulLogin(multiTenant2SamlPage, user2, tenant2RealmSAMLPostLoginPage, "principal=user-tenant2");
            // check the issuer is the correct tenant
            driver.navigate().to(multiTenant2SamlPage.getUriBuilder().clone().path("getAssertionIssuer").build().toASCIIString());
            waitUntilElement(By.xpath("//body")).text().contains("/auth/realms/tenant2");
            // check logout
            multiTenant2SamlPage.logout();
            checkLoggedOut(multiTenant2SamlPage, tenant2RealmSAMLPostLoginPage);
            // check a user in the other tenant doesn't login
            UserRepresentation user1 = createUserRepresentation("user-tenant1", "user-tenant1@redhat.com", "Bill", "Burke", true);
            setPasswordFor(user1, "user-tenant1");
            assertFailedLogin(multiTenant2SamlPage, user1, tenant2RealmSAMLPostLoginPage);
        } finally {
            multiTenant2SamlPage.checkRolesEndPoint(false);
        }
    }

    private static final KeyPair NEW_KEY_PAIR = KeyUtils.generateRsaKeyPair(1024);
    private static final String NEW_KEY_PRIVATE_KEY_PEM = PemUtils.encodeKey(NEW_KEY_PAIR.getPrivate());

    private PublicKey createKeys(String priority) throws Exception {
        PublicKey publicKey = NEW_KEY_PAIR.getPublic();

        ComponentRepresentation rep = new ComponentRepresentation();
        rep.setName("mycomponent");
        rep.setParentId("demo");
        rep.setProviderId(ImportedRsaKeyProviderFactory.ID);
        rep.setProviderType(KeyProvider.class.getName());

        org.keycloak.common.util.MultivaluedHashMap config = new org.keycloak.common.util.MultivaluedHashMap();
        config.addFirst("priority", priority);
        config.addFirst(Attributes.PRIVATE_KEY_KEY, NEW_KEY_PRIVATE_KEY_PEM);
        rep.setConfig(config);

        testRealmResource().components().add(rep);

        return publicKey;
    }

    private void dropKeys(String priority) {
        for (ComponentRepresentation c : testRealmResource().components().query("demo", KeyProvider.class.getName())) {
            if (c.getConfig().getFirst("priority").equals(priority)) {
                testRealmResource().components().component(c.getId()).remove();
                return;
            }
        }
        throw new RuntimeException("Failed to find keys");
    }

    private void testRotatedKeysPropagated(SAMLServlet servletPage, Login loginPage) throws Exception {
        boolean keyDropped = false;
        try {
            log.info("Creating new key");
            createKeys("1000");
            testSuccessfulAndUnauthorizedLogin(servletPage, loginPage);
            log.info("Dropping new key");
            dropKeys("1000");
            keyDropped = true;
            testSuccessfulAndUnauthorizedLogin(servletPage, loginPage);
        } finally {
            if (! keyDropped) {
                dropKeys("1000");
            }
        }
    }

    @Test
    public void employeeSigPostNoIdpKeyTest() throws Exception {
        testRotatedKeysPropagated(employeeSigPostNoIdpKeyServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void employeeSigPostNoIdpKeyTestNoKeyNameInKeyInfo() throws Exception {
        RealmRepresentation r = testRealmResource().toRepresentation();
        r.getAttributes().put(SamlConfigAttributes.SAML_SERVER_SIGNATURE_KEYINFO_KEY_NAME_TRANSFORMER, XmlKeyInfoKeyNameTransformer.NONE.name());
        testRotatedKeysPropagated(employeeSigPostNoIdpKeyServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void employeeSigPostNoIdpKeyTestCertSubjectAsKeyNameInKeyInfo() throws Exception {
        RealmRepresentation r = testRealmResource().toRepresentation();
        r.getAttributes().put(SamlConfigAttributes.SAML_SERVER_SIGNATURE_KEYINFO_KEY_NAME_TRANSFORMER, XmlKeyInfoKeyNameTransformer.CERT_SUBJECT.name());
        testRotatedKeysPropagated(employeeSigPostNoIdpKeyServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void employeeSigPostNoIdpKeyTestKeyIdAsKeyNameInKeyInfo() throws Exception {
        RealmRepresentation r = testRealmResource().toRepresentation();
        r.getAttributes().put(SamlConfigAttributes.SAML_SERVER_SIGNATURE_KEYINFO_KEY_NAME_TRANSFORMER, XmlKeyInfoKeyNameTransformer.KEY_ID.name());
        testRotatedKeysPropagated(employeeSigPostNoIdpKeyServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void employeeSigRedirNoIdpKeyTest() throws Exception {
        testRotatedKeysPropagated(employeeSigRedirNoIdpKeyServletPage, testRealmSAMLRedirectLoginPage);
    }

    @Test
    public void employeeSigRedirNoIdpKeyTestNoKeyNameInKeyInfo() throws Exception {
        RealmRepresentation r = testRealmResource().toRepresentation();
        r.getAttributes().put(SamlConfigAttributes.SAML_SERVER_SIGNATURE_KEYINFO_KEY_NAME_TRANSFORMER, XmlKeyInfoKeyNameTransformer.NONE.name());
        testRotatedKeysPropagated(employeeSigRedirNoIdpKeyServletPage, testRealmSAMLRedirectLoginPage);
    }

    @Test
    public void employeeSigRedirNoIdpKeyTestCertSubjectAsKeyNameInKeyInfo() throws Exception {
        RealmRepresentation r = testRealmResource().toRepresentation();
        r.getAttributes().put(SamlConfigAttributes.SAML_SERVER_SIGNATURE_KEYINFO_KEY_NAME_TRANSFORMER, XmlKeyInfoKeyNameTransformer.CERT_SUBJECT.name());
        testRotatedKeysPropagated(employeeSigRedirNoIdpKeyServletPage, testRealmSAMLRedirectLoginPage);
    }

    @Test
    public void employeeSigRedirNoIdpKeyTestKeyIdAsKeyNameInKeyInfo() throws Exception {
        RealmRepresentation r = testRealmResource().toRepresentation();
        r.getAttributes().put(SamlConfigAttributes.SAML_SERVER_SIGNATURE_KEYINFO_KEY_NAME_TRANSFORMER, XmlKeyInfoKeyNameTransformer.KEY_ID.name());
        testRotatedKeysPropagated(employeeSigRedirNoIdpKeyServletPage, testRealmSAMLRedirectLoginPage);
    }

    @Test
    public void employeeSigRedirOptNoIdpKeyTest() throws Exception {
        testRotatedKeysPropagated(employeeSigRedirOptNoIdpKeyServletPage, testRealmSAMLRedirectLoginPage);
    }

    @Test
    public void employeeSigFrontTest() {
        testSuccessfulAndUnauthorizedLogin(employeeSigFrontServletPage, testRealmSAMLRedirectLoginPage);
    }

    @Test
    public void testLogoutRedirectToExternalPage() throws Exception {
        employeeServletPage.navigateTo();
        assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
        testRealmSAMLPostLoginPage.form().login("bburke", "password");
        assertCurrentUrlStartsWith(employeeServletPage);
        WaitUtils.waitForPageToLoad();

        employeeServletPage.logout();
        adapterLogoutPage.assertCurrent();
    }

    @Test
    public void salesMetadataTest() throws Exception {
        Document doc = IOUtil.loadXML(SAMLServletAdapterTest.class.getResourceAsStream("/adapter-test/keycloak-saml/sp-metadata.xml"));

        IOUtil.modifyDocElementAttribute(doc, "SingleLogoutService", "Location", "8080", System.getProperty("app.server.http.port", null));
        IOUtil.modifyDocElementAttribute(doc, "AssertionConsumerService", "Location", "8080", System.getProperty("app.server.http.port", null));

        ClientRepresentation clientRep = testRealmResource().convertClientDescription(IOUtil.documentToString(doc));

        String appServerUrl;
        if (Boolean.parseBoolean(System.getProperty("app.server.ssl.required"))) {
            appServerUrl = "https://localhost:" + System.getProperty("app.server.https.port", "8543") + "/";
        } else {
            appServerUrl = "http://localhost:" + System.getProperty("app.server.http.port", "8280") + "/";
        }

        clientRep.setAdminUrl(appServerUrl + "sales-metadata/saml");

        try (Response response = testRealmResource().clients().create(clientRep)) {
            Assert.assertEquals(201, response.getStatus());
        }

        testSuccessfulAndUnauthorizedLogin(salesMetadataServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void salesPostTestCompositeRoleForUser() {
        UserRepresentation topGroupUser = createUserRepresentation("topGroupUser", "top@redhat.com", "", "", true);
        setPasswordFor(topGroupUser, PASSWORD);

        assertSuccessfulLogin(salesPostServletPage, topGroupUser, testRealmSAMLPostLoginPage, "principal=topgroupuser");

        salesPostServletPage.logout();
        checkLoggedOut(salesPostServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void salesPostTest() {
        testSuccessfulAndUnauthorizedLogin(salesPostServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void salesPostEncTest() {
        testSuccessfulAndUnauthorizedLogin(salesPostEncServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void salesPostEncSignedAssertionsOnlyTest() throws Exception {
        testSuccessfulAndUnauthorizedLogin(salesPostEncSignAssertionsOnlyServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void salesPostEncSignedAssertionsAndDocumentTest() throws Exception {
        try (Closeable client = ClientAttributeUpdater.forClient(adminClient, testRealmPage.getAuthRealm(), SalesPostEncServlet.CLIENT_NAME)
          .setAttribute(SamlConfigAttributes.SAML_ASSERTION_SIGNATURE, "true")
          .setAttribute(SamlConfigAttributes.SAML_SERVER_SIGNATURE, "true")
          .update()) {
            testSuccessfulAndUnauthorizedLogin(salesPostEncServletPage, testRealmSAMLPostLoginPage);
        } finally {
            salesPostEncServletPage.logout();
        }
    }

    @Test
    public void salesPostEncRejectConsent() throws Exception {
        try (Closeable client = ClientAttributeUpdater.forClient(adminClient, testRealmPage.getAuthRealm(), SalesPostEncServlet.CLIENT_NAME)
          .setConsentRequired(true)
          .update()) {
            new SamlClientBuilder()
              .navigateTo(salesPostEncServletPage.toString())
              .processSamlResponse(Binding.POST).build()
              .login().user(bburkeUser).build()
              .consentRequired().approveConsent(false).build()
              .processSamlResponse(Binding.POST).build()

              .execute(r -> {
                  Assert.assertThat(r, statusCodeIsHC(Response.Status.OK));
                  Assert.assertThat(r, bodyHC(containsString("urn:oasis:names:tc:SAML:2.0:status:RequestDenied")));  // TODO: revisit - should the HTTP status be 403 too?
              });
        } finally {
            salesPostEncServletPage.logout();
        }
    }

    @Test
    public void salesPostRejectConsent() throws Exception {
        try (Closeable client = ClientAttributeUpdater.forClient(adminClient, testRealmPage.getAuthRealm(), SalesPostServlet.CLIENT_NAME)
          .setConsentRequired(true)
          .update()) {
            new SamlClientBuilder()
              .navigateTo(salesPostServletPage.toString())
              .processSamlResponse(Binding.POST).build()
              .login().user(bburkeUser).build()
              .consentRequired().approveConsent(false).build()
              .processSamlResponse(Binding.POST).build()

              .execute(r -> {
                  Assert.assertThat(r, statusCodeIsHC(Response.Status.OK));
                  Assert.assertThat(r, bodyHC(containsString("urn:oasis:names:tc:SAML:2.0:status:RequestDenied")));  // TODO: revisit - should the HTTP status be 403 too?
              });
        } finally {
            salesPostServletPage.logout();
        }
    }

    @Test
    public void salesPostPassiveTest() {
        salesPostPassiveServletPage.navigateTo();

        if (forbiddenIfNotAuthenticated) {
            assertOnForbiddenPage();
        } else {
            waitUntilElement(By.xpath("//body")).text().contains("principal=null");
        }

        assertSuccessfulLogin(salesPostServletPage, bburkeUser, testRealmSAMLPostLoginPage, "principal=bburke");

        assertSuccessfullyLoggedIn(salesPostPassiveServletPage, "principal=bburke");

        salesPostPassiveServletPage.logout();
        salesPostPassiveServletPage.navigateTo();

        if (forbiddenIfNotAuthenticated) {
            assertOnForbiddenPage();
        } else {
            waitUntilElement(By.xpath("//body")).text().contains("principal=null");
        }

        assertForbiddenLogin(salesPostServletPage, "unauthorized", "password", testRealmSAMLPostLoginPage, "principal=");
        assertForbidden(salesPostPassiveServletPage, "principal=");

        salesPostPassiveServletPage.logout();
    }

    @Test
    public void salesPostSigTest() {
        testSuccessfulAndUnauthorizedLogin(salesPostSigServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    // https://issues.jboss.org/browse/KEYCLOAK-3971
    public void salesPostSigTestUnicodeCharacters() {
        final String username = "ěščřžýáíRoàåéèíñòøöùüßÅÄÖÜ";
        UserRepresentation user = UserBuilder
          .edit(createUserRepresentation(username, "xyz@redhat.com", "ěščřžýáí", "RoàåéèíñòøöùüßÅÄÖÜ", true))
          .addPassword(PASSWORD)
          .build();
        String userId = createUserAndResetPasswordWithAdminClient(testRealmResource(), user, PASSWORD);
        final RoleScopeResource realmRoleRes = testRealmResource().users().get(userId).roles().realmLevel();
        List<RoleRepresentation> availableRoles = realmRoleRes.listAvailable();
        realmRoleRes.add(availableRoles.stream().filter(r -> r.getName().equalsIgnoreCase("manager")).collect(Collectors.toList()));

        UserRepresentation storedUser = testRealmResource().users().get(userId).toRepresentation();

        Assert.assertThat(storedUser, notNullValue());
        Assert.assertThat("Database seems to be unable to store Unicode for username. Refer to KEYCLOAK-3439 and related issues.", storedUser.getUsername(), equalToIgnoringCase(username));

        assertSuccessfulLogin(salesPostSigServletPage, user, testRealmSAMLPostLoginPage, "principal=" + storedUser.getUsername());

        salesPostSigServletPage.logout();
        checkLoggedOut(salesPostSigServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    // https://issues.jboss.org/browse/KEYCLOAK-3971
    public void employeeSigTestUnicodeCharacters() {
        final String username = "ěščřžýáíRoàåéèíñòøöùüßÅÄÖÜ";
        UserRepresentation user = UserBuilder
          .edit(createUserRepresentation(username, "xyz@redhat.com", "ěščřžýáí", "RoàåéèíñòøöùüßÅÄÖÜ", true))
          .addPassword(PASSWORD)
          .build();
        String userId = createUserAndResetPasswordWithAdminClient(testRealmResource(), user, PASSWORD);
        final RoleScopeResource realmRoleRes = testRealmResource().users().get(userId).roles().realmLevel();
        List<RoleRepresentation> availableRoles = realmRoleRes.listAvailable();
        realmRoleRes.add(availableRoles.stream().filter(r -> r.getName().equalsIgnoreCase("manager")).collect(Collectors.toList()));

        UserRepresentation storedUser = testRealmResource().users().get(userId).toRepresentation();

        Assert.assertThat(storedUser, notNullValue());
        Assert.assertThat("Database seems to be unable to store Unicode for username. Refer to KEYCLOAK-3439 and related issues.", storedUser.getUsername(), equalToIgnoringCase(username));

        assertSuccessfulLogin(employeeSigServletPage, user, testRealmSAMLRedirectLoginPage, "principal=" + storedUser.getUsername());

        employeeSigServletPage.logout();
        checkLoggedOut(employeeSigServletPage, testRealmSAMLRedirectLoginPage);
    }

    @Test
    public void salesPostSigEmailTest() {
        testSuccessfulAndUnauthorizedLogin(salesPostSigEmailServletPage, testRealmSAMLPostLoginPage, "principal=bburke@redhat.com");
    }

    @Test
    public void salesPostSigStaxParsingFlawEmailTest() {
        UserRepresentation user = createUserRepresentation("bburke-additional-domain", "bburke@redhat.com.additional.domain", "Bill", "Burke", true);
        setPasswordFor(user, PASSWORD);

        String resultPage = new SamlClientBuilder()
          .navigateTo(salesPostSigEmailServletPage.buildUri())
          .processSamlResponse(Binding.POST).build()
          .login().user(user).build()
          .processSamlResponse(Binding.POST)
            .transformString(s -> {
                Assert.assertThat(s, org.hamcrest.Matchers.containsString(">bburke@redhat.com.additional.domain<"));
                s = s.replaceAll("bburke@redhat.com.additional.domain", "bburke@redhat.com<!-- comment -->.additional.domain");
                return s;
            })
            .build()
          .executeAndTransform(resp -> EntityUtils.toString(resp.getEntity()));

        Assert.assertThat(resultPage, org.hamcrest.Matchers.containsString("principal=bburke@redhat.com.additional.domain"));
    }

    @Test
    public void salesPostSigChangeContents() {
        UserRepresentation user = createUserRepresentation("bburke-additional-domain", "bburke@redhat.com.additional.domain", "Bill", "Burke", true);
        setPasswordFor(user, PASSWORD);

        String resultPage = new SamlClientBuilder()
          .navigateTo(salesPostSigEmailServletPage.buildUri())
          .processSamlResponse(Binding.POST).build()
          .login().user(user).build()
          .processSamlResponse(Binding.POST)
            .transformString(s -> {
                Assert.assertThat(s, org.hamcrest.Matchers.containsString(">bburke@redhat.com.additional.domain<"));
                s = s.replaceAll("bburke@redhat.com.additional.domain", "bburke@redhat.com");
                return s;
            })
            .build()
          .executeAndTransform(resp -> EntityUtils.toString(resp.getEntity()));

        Assert.assertThat(resultPage, org.hamcrest.Matchers.containsString("INVALID_SIGNATURE"));
    }

    @Test
    public void salesPostSigPersistentTest() {
        salesPostSigPersistentServletPage.navigateTo();
        testRealmSAMLPostLoginPage.form().login(bburkeUser);
        waitUntilElement(By.xpath("//body")).text().not().contains("bburke");
        waitUntilElement(By.xpath("//body")).text().contains("principal=G-");

        salesPostSigPersistentServletPage.logout();
        checkLoggedOut(salesPostSigPersistentServletPage, testRealmSAMLPostLoginPage);

        assertForbiddenLogin(salesPostSigPersistentServletPage, "unauthorized", "password", testRealmSAMLPostLoginPage, "principal=");
        salesPostSigPersistentServletPage.logout();
        checkLoggedOut(salesPostSigPersistentServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void salesPostSigTransientTest() {
        salesPostSigTransientServletPage.navigateTo();
        testRealmSAMLPostLoginPage.form().login(bburkeUser);
        waitUntilElement(By.xpath("//body")).text().not().contains("bburke");
        waitUntilElement(By.xpath("//body")).text().contains("principal=G-");

        salesPostSigTransientServletPage.logout();
        checkLoggedOut(salesPostSigTransientServletPage, testRealmSAMLPostLoginPage);

        assertForbiddenLogin(salesPostSigTransientServletPage, "unauthorized", "password", testRealmSAMLPostLoginPage, "principal=");
        salesPostSigTransientServletPage.logout();
        checkLoggedOut(salesPostSigTransientServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void idpInitiatedLoginTest() {
        samlidpInitiatedLoginPage.setAuthRealm(SAMLSERVLETDEMO);
        samlidpInitiatedLoginPage.setUrlName("employee2");
        samlidpInitiatedLoginPage.navigateTo();
        samlidpInitiatedLoginPage.form().login(bburkeUser);

        waitUntilElement(By.xpath("//body")).text().contains("principal=bburke");

        assertSuccessfullyLoggedIn(salesPostSigServletPage, "principal=bburke");

        employee2ServletPage.logout();
        checkLoggedOut(employee2ServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void idpInitiatedUnauthorizedLoginTest() {
        samlidpInitiatedLoginPage.setAuthRealm(SAMLSERVLETDEMO);
        samlidpInitiatedLoginPage.setUrlName("employee2");
        samlidpInitiatedLoginPage.navigateTo();
        samlidpInitiatedLoginPage.form().login("unauthorized", "password");

        waitUntilElement(By.xpath("//body")).text().not().contains("bburke");
        //Different 403 status page on EAP and Wildfly
        Assert.assertTrue(driver.getPageSource().contains("Forbidden")
                || driver.getPageSource().contains(FORBIDDEN_TEXT)
                || driver.getPageSource().contains(WEBSPHERE_FORBIDDEN_TEXT)); // WebSphere

        assertForbidden(employee2ServletPage, "principal=");
        employee2ServletPage.logout();
        checkLoggedOut(employee2ServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void testSavedPostRequest() {
        inputPortalPage.navigateTo();
        assertCurrentUrlStartsWith(inputPortalPage);
        inputPortalPage.execute("hello");

        assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
        testRealmLoginPage.form().login("bburke@redhat.com", "password");
        Assert.assertEquals(driver.getCurrentUrl(), inputPortalPage + "/secured/post");
        waitUntilElement(By.xpath("//body")).text().contains("parameter=hello");

        // test that user principal and KeycloakSecurityContext available
        driver.navigate().to(inputPortalPage + "/insecure");
        waitUntilElement(By.xpath("//body")).text().contains("Insecure Page");

        if (System.getProperty("insecure.user.principal.unsupported") == null) waitUntilElement(By.xpath("//body")).text().contains("UserPrincipal");

        // test logout

        inputPortalPage.logout();

        // test unsecured POST KEYCLOAK-901

        Client client = ClientBuilder.newClient();
        Form form = new Form();
        form.param("parameter", "hello");
        String text = client.target(inputPortalPage + "/unsecured").request().post(Entity.form(form), String.class);
        Assert.assertTrue(text.contains("parameter=hello"));
        client.close();
    }

    @Test
    public void testPostSimpleLoginLogoutIdpInitiatedRedirectTo() {
        samlidpInitiatedLoginPage.setAuthRealm(SAMLSERVLETDEMO);
        samlidpInitiatedLoginPage.setUrlName("sales-post2");
        samlidpInitiatedLoginPage.navigateTo();

        samlidpInitiatedLoginPage.form().login(bburkeUser);
        assertCurrentUrlStartsWith(salesPost2ServletPage);
        Assert.assertThat(driver.getCurrentUrl(), endsWith("/foo"));
        waitUntilElement(By.xpath("//body")).text().contains("principal=bburke");
        salesPost2ServletPage.logout();
        checkLoggedOut(salesPost2ServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void salesPostAssertionAndResponseSigTest() {
        testSuccessfulAndUnauthorizedLogin(salesPostAssertionAndResponseSigPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void testPostBadAssertionSignature() {
        badAssertionSalesPostSigPage.navigateTo();
        assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
        testRealmSAMLPostLoginPage.form().login("bburke", "password");

        waitUntilElement(By.xpath("//body")).text().contains("Error info: SamlAuthenticationError [reason=INVALID_SIGNATURE");
        Assert.assertEquals(driver.getCurrentUrl(), badAssertionSalesPostSigPage + "/saml");
    }

    @Test
    public void testMissingAssertionSignature() {
        missingAssertionSigPage.navigateTo();
        assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
        testRealmSAMLPostLoginPage.form().login("bburke", "password");

        waitUntilElement(By.xpath("//body")).text().contains("Error info: SamlAuthenticationError [reason=INVALID_SIGNATURE");
        Assert.assertEquals(driver.getCurrentUrl(), missingAssertionSigPage + "/saml");
    }

    @Test
    public void testErrorHandlingUnsigned() throws Exception {
        SAML2ErrorResponseBuilder builder = new SAML2ErrorResponseBuilder()
                .destination(employeeSigServletPage.toString() + "/saml")
                .issuer("http://localhost:" + System.getProperty("auth.server.http.port", "8180") + "/realms/demo")
                .status(JBossSAMLURIConstants.STATUS_REQUEST_DENIED.get());
        Document document = builder.buildDocument();

        new SamlClientBuilder()
                .addStep((client, currentURI, currentResponse, context) ->
                        Binding.REDIRECT.createSamlUnsignedResponse(URI.create(employeeSigServletPage.toString() + "/saml"), null, document))
                .execute(closeableHttpResponse -> Assert.assertThat(closeableHttpResponse, bodyHC(containsString("INVALID_SIGNATURE"))));
    }

    @Test
    public void testErrorHandlingSigned() throws Exception {
        SAML2ErrorResponseBuilder builder = new SAML2ErrorResponseBuilder()
                .destination(employeeSigServletPage.toString() + "/saml")
                .issuer("http://localhost:" + System.getProperty("auth.server.http.port", "8180") + "/realms/demo")
                .status(JBossSAMLURIConstants.STATUS_REQUEST_DENIED.get());
        Document document = builder.buildDocument();

        new SamlClientBuilder()
                .addStep((client, currentURI, currentResponse, context) ->
                        Binding.REDIRECT.createSamlSignedResponse(URI.create(employeeSigServletPage.toString() + "/saml"), null, document, REALM_PRIVATE_KEY, REALM_PUBLIC_KEY))
                .execute(closeableHttpResponse -> Assert.assertThat(closeableHttpResponse, bodyHC(containsString("ERROR_STATUS"))));
    }

    @Test
    public void testRelayStateEncoding() throws Exception {
        // this test has a hardcoded SAMLRequest and we hack a SP face servlet to get the SAMLResponse so we can look
        // at the relay state
        employeeServletPage.navigateTo();
        assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
        testRealmSAMLPostLoginPage.form().login("bburke", "password");
        assertCurrentUrlStartsWith(employeeServletPage);
        waitForPageToLoad();
        String pageSource = driver.getPageSource();
        Assert.assertThat(pageSource, containsString("Relay state: " + SamlSPFacade.RELAY_STATE));
        Assert.assertThat(pageSource, not(containsString("SAML response: null")));
    }

    private static List<String> parseCommaSeparatedAttributes(String body, String attribute) {
        int start = body.indexOf(attribute) + attribute.length();
        if (start == -1) {
            return Collections.emptyList();
        }
        int end = body.indexOf(System.getProperty("line.separator"), start);
        if (end == -1) {
            end = body.length();
        }
        String values = body.substring(start, end);
        String[] parts = values.split(",");
        return Arrays.asList(parts);
    }

    @Test
    public void testUserAttributeStatementMapperUserGroupsAggregate() throws Exception {
        UserResource userResource = ApiUtil.findUserByUsernameId(testRealmResource(), "bburke");
        UserRepresentation user = userResource.toRepresentation();
        user.setAttributes(new HashMap<>());
        user.getAttributes().put("group-value", Arrays.asList("user-value1"));
        userResource.update(user);
        GroupRepresentation group1 = new GroupRepresentation();
        group1.setName("group1");
        group1.setAttributes(new HashMap<>());
        group1.getAttributes().put("group-value", Arrays.asList("value1", "value2"));
        testRealmResource().groups().add(group1);
        group1 = testRealmResource().getGroupByPath("/group1");
        userResource.joinGroup(group1.getId());

        ClientResource clientResource = ApiUtil.findClientResourceByClientId(testRealmResource(), AbstractSamlTest.SAML_CLIENT_ID_EMPLOYEE_2);
        ProtocolMappersResource protocolMappersResource = clientResource.getProtocolMappers();

        Map<String, String> config = new LinkedHashMap<>();
        config.put("attribute.nameformat", "Basic");
        config.put("user.attribute", "group-value");
        config.put("attribute.name", "group-attribute");
        config.put("aggregate.attrs", "true");
        createProtocolMapper(protocolMappersResource, "group-value", "saml", "saml-user-attribute-mapper", config);

        try {
            employee2ServletPage.navigateTo();
            assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
            testRealmSAMLPostLoginPage.form().login("bburke", "password");

            driver.navigate().to(employee2ServletPage.toString() + "/getAttributes");
            waitForPageToLoad();

            String body = driver.findElement(By.xpath("//body")).getText();
            List<String> values = parseCommaSeparatedAttributes(body, " group-attribute: ");
            Assert.assertEquals(3, values.size());
            Assert.assertTrue(values.contains("user-value1"));
            Assert.assertTrue(values.contains("value1"));
            Assert.assertTrue(values.contains("value2"));

            employee2ServletPage.logout();
            checkLoggedOut(employee2ServletPage, testRealmSAMLPostLoginPage);
        } finally {
            // revert
            user.getAttributes().remove("group-value");
            userResource.update(user);
            userResource.leaveGroup(group1.getId());
            testRealmResource().groups().group(group1.getId()).remove();
            ProtocolMapperRepresentation mapper = ProtocolMapperUtil.getMapperByNameAndProtocol(protocolMappersResource, "saml", "group-value");
            protocolMappersResource.delete(mapper.getId());
        }
    }

    @Test
    public void testUserAttributeStatementMapperUserGroupsNoAggregate() throws Exception {
        UserResource userResource = ApiUtil.findUserByUsernameId(testRealmResource(), "bburke");
        UserRepresentation user = userResource.toRepresentation();
        user.setAttributes(new HashMap<>());
        user.getAttributes().put("group-value", Arrays.asList("user-value1"));
        userResource.update(user);
        GroupRepresentation group1 = new GroupRepresentation();
        group1.setName("group1");
        group1.setAttributes(new HashMap<>());
        group1.getAttributes().put("group-value", Arrays.asList("value1", "value2"));
        testRealmResource().groups().add(group1);
        group1 = testRealmResource().getGroupByPath("/group1");
        userResource.joinGroup(group1.getId());

        ClientResource clientResource = ApiUtil.findClientResourceByClientId(testRealmResource(), AbstractSamlTest.SAML_CLIENT_ID_EMPLOYEE_2);
        ProtocolMappersResource protocolMappersResource = clientResource.getProtocolMappers();

        Map<String, String> config = new LinkedHashMap<>();
        config.put("attribute.nameformat", "Basic");
        config.put("user.attribute", "group-value");
        config.put("attribute.name", "group-attribute");
        createProtocolMapper(protocolMappersResource, "group-value", "saml", "saml-user-attribute-mapper", config);

        try {
            employee2ServletPage.navigateTo();
            assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
            testRealmSAMLPostLoginPage.form().login("bburke", "password");

            driver.navigate().to(employee2ServletPage.toString() + "/getAttributes");
            waitForPageToLoad();

            String body = driver.findElement(By.xpath("//body")).getText();
            List<String> values = parseCommaSeparatedAttributes(body, " group-attribute: ");
            Assert.assertEquals(1, values.size());
            Assert.assertTrue(values.contains("user-value1"));

            employee2ServletPage.logout();
            checkLoggedOut(employee2ServletPage, testRealmSAMLPostLoginPage);
        } finally {
            // revert
            user.getAttributes().remove("group-value");
            userResource.update(user);
            userResource.leaveGroup(group1.getId());
            testRealmResource().groups().group(group1.getId()).remove();
            ProtocolMapperRepresentation mapper = ProtocolMapperUtil.getMapperByNameAndProtocol(protocolMappersResource, "saml", "group-value");
            protocolMappersResource.delete(mapper.getId());
        }
    }

    @Test
    public void testUserAttributeStatementMapperGroupsAggregate() throws Exception {
        UserResource userResource = ApiUtil.findUserByUsernameId(testRealmResource(), "bburke");
        GroupRepresentation group1 = new GroupRepresentation();
        group1.setName("group1");
        group1.setAttributes(new HashMap<>());
        group1.getAttributes().put("group-value", Arrays.asList("value1", "value2"));
        testRealmResource().groups().add(group1);
        group1 = testRealmResource().getGroupByPath("/group1");
        userResource.joinGroup(group1.getId());
        GroupRepresentation group2 = new GroupRepresentation();
        group2.setName("group2");
        group2.setAttributes(new HashMap<>());
        group2.getAttributes().put("group-value", Arrays.asList("value2", "value3"));
        testRealmResource().groups().add(group2);
        group2 = testRealmResource().getGroupByPath("/group2");
        userResource.joinGroup(group2.getId());

        ClientResource clientResource = ApiUtil.findClientResourceByClientId(testRealmResource(), AbstractSamlTest.SAML_CLIENT_ID_EMPLOYEE_2);
        ProtocolMappersResource protocolMappersResource = clientResource.getProtocolMappers();

        Map<String, String> config = new LinkedHashMap<>();
        config.put("attribute.nameformat", "Basic");
        config.put("user.attribute", "group-value");
        config.put("attribute.name", "group-attribute");
        config.put("aggregate.attrs", "true");
        createProtocolMapper(protocolMappersResource, "group-value", "saml", "saml-user-attribute-mapper", config);

        try {
            employee2ServletPage.navigateTo();
            assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
            testRealmSAMLPostLoginPage.form().login("bburke", "password");

            driver.navigate().to(employee2ServletPage.toString() + "/getAttributes");
            waitForPageToLoad();

            String body = driver.findElement(By.xpath("//body")).getText();
            List<String> values = parseCommaSeparatedAttributes(body, " group-attribute: ");
            Assert.assertEquals(3, values.size());
            Assert.assertTrue(values.contains("value1"));
            Assert.assertTrue(values.contains("value2"));
            Assert.assertTrue(values.contains("value3"));

            employee2ServletPage.logout();
            checkLoggedOut(employee2ServletPage, testRealmSAMLPostLoginPage);
        } finally {
            // revert
            userResource.leaveGroup(group1.getId());
            testRealmResource().groups().group(group1.getId()).remove();
            userResource.leaveGroup(group2.getId());
            testRealmResource().groups().group(group2.getId()).remove();
            ProtocolMapperRepresentation mapper = ProtocolMapperUtil.getMapperByNameAndProtocol(protocolMappersResource, "saml", "group-value");
            protocolMappersResource.delete(mapper.getId());
        }
    }

    @Test
    public void testUserAttributeStatementMapperGroupsNoAggregate() throws Exception {
        UserResource userResource = ApiUtil.findUserByUsernameId(testRealmResource(), "bburke");
        GroupRepresentation group1 = new GroupRepresentation();
        group1.setName("group1");
        group1.setAttributes(new HashMap<>());
        group1.getAttributes().put("group-value", Arrays.asList("value1", "value2"));
        testRealmResource().groups().add(group1);
        group1 = testRealmResource().getGroupByPath("/group1");
        userResource.joinGroup(group1.getId());
        GroupRepresentation group2 = new GroupRepresentation();
        group2.setName("group2");
        group2.setAttributes(new HashMap<>());
        group2.getAttributes().put("group-value", Arrays.asList("value2", "value3"));
        testRealmResource().groups().add(group2);
        group2 = testRealmResource().getGroupByPath("/group2");
        userResource.joinGroup(group2.getId());

        ClientResource clientResource = ApiUtil.findClientResourceByClientId(testRealmResource(), AbstractSamlTest.SAML_CLIENT_ID_EMPLOYEE_2);
        ProtocolMappersResource protocolMappersResource = clientResource.getProtocolMappers();

        Map<String, String> config = new LinkedHashMap<>();
        config.put("attribute.nameformat", "Basic");
        config.put("user.attribute", "group-value");
        config.put("attribute.name", "group-attribute");
        createProtocolMapper(protocolMappersResource, "group-value", "saml", "saml-user-attribute-mapper", config);

        try {
            employee2ServletPage.navigateTo();
            assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
            testRealmSAMLPostLoginPage.form().login("bburke", "password");

            driver.navigate().to(employee2ServletPage.toString() + "/getAttributes");
            waitForPageToLoad();

            String body = driver.findElement(By.xpath("//body")).getText();
            List<String> values = parseCommaSeparatedAttributes(body, " group-attribute: ");
            Assert.assertEquals(2, values.size());
            Assert.assertTrue((values.contains("value1") && values.contains("value2"))
                    || (values.contains("value2") && values.contains("value3")));

            employee2ServletPage.logout();
            checkLoggedOut(employee2ServletPage, testRealmSAMLPostLoginPage);
        } finally {
            // revert
            userResource.leaveGroup(group1.getId());
            testRealmResource().groups().group(group1.getId()).remove();
            userResource.leaveGroup(group2.getId());
            testRealmResource().groups().group(group2.getId()).remove();
            ProtocolMapperRepresentation mapper = ProtocolMapperUtil.getMapperByNameAndProtocol(protocolMappersResource, "saml", "group-value");
            protocolMappersResource.delete(mapper.getId());
        }
    }

    @Test
    public void testAttributes() throws Exception {
        ClientResource clientResource = ApiUtil.findClientResourceByClientId(testRealmResource(), AbstractSamlTest.SAML_CLIENT_ID_EMPLOYEE_2);
        ProtocolMappersResource protocolMappersResource = clientResource.getProtocolMappers();

        Map<String, String> config = new LinkedHashMap<>();
        config.put("attribute.nameformat", "Basic");
        config.put("user.attribute", "topAttribute");
        config.put("attribute.name", "topAttribute");
        createProtocolMapper(protocolMappersResource, "topAttribute", "saml", "saml-user-attribute-mapper", config);

        config = new LinkedHashMap<>();
        config.put("attribute.nameformat", "Basic");
        config.put("user.attribute", "level2Attribute");
        config.put("attribute.name", "level2Attribute");
        createProtocolMapper(protocolMappersResource, "level2Attribute", "saml", "saml-user-attribute-mapper", config);

        config = new LinkedHashMap<>();
        config.put("attribute.nameformat", "Basic");
        config.put("single", "true");
        config.put("attribute.name", "group");
        createProtocolMapper(protocolMappersResource, "groups", "saml", "saml-group-membership-mapper", config);

        setRolesToCheck("manager,user");

        employee2ServletPage.navigateTo();
        assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
        testRealmSAMLPostLoginPage.form().login("level2GroupUser", "password");

        driver.navigate().to(employee2ServletPage.toString() + "/getAttributes");
        waitUntilElement(By.xpath("//body")).text().contains("topAttribute: true");
        waitUntilElement(By.xpath("//body")).text().contains("level2Attribute: true");
        waitUntilElement(By.xpath("//body")).text().contains("attribute email: level2@redhat.com");
        waitUntilElement(By.xpath("//body")).text().not().contains("group: []");
        waitUntilElement(By.xpath("//body")).text().not().contains("group: null");
        waitUntilElement(By.xpath("//body")).text().contains("group: [level2]");

        employee2ServletPage.logout();
        checkLoggedOut(employee2ServletPage, testRealmSAMLPostLoginPage);

        setRolesToCheck("manager,employee,user");

        employee2ServletPage.navigateTo();
        assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
        testRealmSAMLPostLoginPage.form().login(bburkeUser);

        driver.navigate().to(employee2ServletPage.toString() + "/getAttributes");
        waitUntilElement(By.xpath("//body")).text().contains("attribute email: bburke@redhat.com");
        waitUntilElement(By.xpath("//body")).text().contains("friendlyAttribute email: bburke@redhat.com");
        waitUntilElement(By.xpath("//body")).text().contains("phone: 617");
        waitUntilElement(By.xpath("//body")).text().contains("friendlyAttribute phone: null");

        employee2ServletPage.logout();
        checkLoggedOut(employee2ServletPage, testRealmSAMLPostLoginPage);

        config = new LinkedHashMap<>();
        config.put("attribute.value", "hard");
        config.put("attribute.nameformat", "Basic");
        config.put("attribute.name", "hardcoded-attribute");
        createProtocolMapper(protocolMappersResource, "hardcoded-attribute", "saml", "saml-hardcode-attribute-mapper", config);

        config = new LinkedHashMap<>();
        config.put("role", "hardcoded-role");
        createProtocolMapper(protocolMappersResource, "hardcoded-role", "saml", "saml-hardcode-role-mapper", config);

        config = new LinkedHashMap<>();
        config.put("new.role.name", "pee-on");
        config.put("role", "http://localhost:8280/employee/.employee");
        createProtocolMapper(protocolMappersResource, "renamed-employee-role", "saml", "saml-role-name-mapper", config);

        for (ProtocolMapperRepresentation mapper : clientResource.toRepresentation().getProtocolMappers()) {
            if (mapper.getName().equals("role-list")) {
                protocolMappersResource.delete(mapper.getId());

                mapper.setId(null);
                mapper.getConfig().put(RoleListMapper.SINGLE_ROLE_ATTRIBUTE, "true");
                mapper.getConfig().put(AttributeStatementHelper.SAML_ATTRIBUTE_NAME, "memberOf");
                protocolMappersResource.createMapper(mapper);
            }
        }

        setRolesToCheck("pee-on,el-jefe,manager,hardcoded-role");

        config = new LinkedHashMap<>();
        config.put("new.role.name", "el-jefe");
        config.put("role", "user");
        createProtocolMapper(protocolMappersResource, "renamed-role", "saml", "saml-role-name-mapper", config);

        employee2ServletPage.navigateTo();
        assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
        testRealmSAMLPostLoginPage.form().login(bburkeUser);

        driver.navigate().to(employee2ServletPage.toString() + "/getAttributes");
        waitUntilElement(By.xpath("//body")).text().contains("hardcoded-attribute: hard");
        employee2ServletPage.checkRolesEndPoint(false);
        employee2ServletPage.logout();
        checkLoggedOut(employee2ServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    public void idpMetadataValidation() throws Exception {
        driver.navigate().to(authServerPage.toString() + "/realms/" + SAMLSERVLETDEMO + "/protocol/saml/descriptor");
        validateXMLWithSchema(driver.getPageSource(), "/adapter-test/keycloak-saml/metadata-schema/saml-schema-metadata-2.0.xsd");
    }


    @Test
    public void spMetadataValidation() throws Exception {
        ClientResource clientResource = ApiUtil.findClientResourceByClientId(testRealmResource(), AbstractSamlTest.SAML_CLIENT_ID_SALES_POST_SIG);
        ClientRepresentation representation = clientResource.toRepresentation();
        Client client = ClientBuilder.newClient();
        WebTarget target = client.target(authServerPage.toString() + "/admin/realms/" + SAMLSERVLETDEMO + "/clients/" + representation.getId() + "/installation/providers/saml-sp-descriptor");
        Response response = target.request().header(HttpHeaders.AUTHORIZATION, "Bearer " + adminClient.tokenManager().getAccessToken().getToken()).get();
        validateXMLWithSchema(response.readEntity(String.class), "/adapter-test/keycloak-saml/metadata-schema/saml-schema-metadata-2.0.xsd");
        response.close();
    }

    @Test
    //KEYCLOAK-4020
    public void testBooleanAttribute() throws Exception {
        new SamlClientBuilder()
          .authnRequest(getAuthServerSamlEndpoint(SAMLSERVLETDEMO), AbstractSamlTest.SAML_CLIENT_ID_EMPLOYEE_2, getAppServerSamlEndpoint(employee2ServletPage).toString(), Binding.POST).build()
          .login().user(bburkeUser).build()
          .processSamlResponse(Binding.POST)
            .transformDocument(responseDoc -> {
                Element attribute = responseDoc.createElement("saml:Attribute");
                attribute.setAttribute("Name", "boolean-attribute");
                attribute.setAttribute("NameFormat", "urn:oasis:names:tc:SAML:2.0:attrname-format:basic");

                Element attributeValue = responseDoc.createElement("saml:AttributeValue");
                attributeValue.setAttribute("xmlns:xs", "http://www.w3.org/2001/XMLSchema");
                attributeValue.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
                attributeValue.setAttribute("xsi:type", "xs:boolean");
                attributeValue.setTextContent("true");

                attribute.appendChild(attributeValue);
                IOUtil.appendChildInDocument(responseDoc, "samlp:Response/saml:Assertion/saml:AttributeStatement", attribute);

                return responseDoc;
            })
            .build()

          .navigateTo(employee2ServletPage.toString() + "/getAttributes")

          .execute(r -> {
              Assert.assertThat(r, statusCodeIsHC(Response.Status.OK));
              Assert.assertThat(r, bodyHC(containsString("boolean-attribute: true")));
          });
    }

    @Test
    public void testNameIDUnset() throws Exception {
        new SamlClientBuilder()
          .navigateTo(employee2ServletPage.toString())
          .processSamlResponse(Binding.POST).build()
          .login().user(bburkeUser).build()
          .processSamlResponse(Binding.POST)
            .transformDocument(responseDoc -> {
                XPathFactory xPathfactory = XPathFactory.newInstance();
                XPath xpath = xPathfactory.newXPath();
                XPathExpression expr = xpath.compile("//*[local-name()='NameID']");

                NodeList nodeList = (NodeList) expr.evaluate(responseDoc, XPathConstants.NODESET);
                Assert.assertThat(nodeList.getLength(), is(1));

                final Node nameIdNode = nodeList.item(0);
                nameIdNode.getParentNode().removeChild(nameIdNode);

                return responseDoc;
            })
            .build()

          .navigateTo(employee2ServletPage.toString())

          .execute(r -> {
              Assert.assertThat(r, statusCodeIsHC(Response.Status.OK));
              Assert.assertThat(r, bodyHC(allOf(containsString("principal="), not(containsString("500")))));
          });
    }

    // KEYCLOAK-4329
    @Test
    public void testEmptyKeyInfoElement() {
        log.debug("Log in using idp initiated login");
        SAMLDocumentHolder documentHolder = new SamlClientBuilder()
          .idpInitiatedLogin(getAuthServerSamlEndpoint(SAMLSERVLETDEMO), "sales-post-sig-email").build()
          .login().user(bburkeUser).build()
          .getSamlResponse(Binding.POST);


        log.debug("Removing KeyInfo from Keycloak response");
        Document responseDoc = documentHolder.getSamlDocument();
        IOUtil.removeElementFromDoc(responseDoc, "samlp:Response/dsig:Signature/dsig:KeyInfo");

        CloseableHttpResponse response = null;
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpClientContext context = HttpClientContext.create();

            log.debug("Sending response to SP");
            HttpUriRequest post = SamlClient.Binding.POST.createSamlUnsignedResponse(getAppServerSamlEndpoint(salesPostSigEmailServletPage), null, responseDoc);
            response = client.execute(post, context);
            System.out.println(EntityUtils.toString(response.getEntity()));
            Assert.assertThat(response, statusCodeIsHC(Response.Status.FOUND));
            response.close();

            HttpGet get = new HttpGet(salesPostSigEmailServletPage.toString());
            response = client.execute(get);
            Assert.assertThat(response, statusCodeIsHC(Response.Status.OK));
            Assert.assertThat(response, bodyHC(containsString("principal=bburke")));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            if (response != null) {
                EntityUtils.consumeQuietly(response.getEntity());
                try { response.close(); } catch (IOException ex) { }
            }
        }
    }

    @Test
    // KEYCLOAK-4141
    public void testDifferentCookieName() {
        assertSuccessfulLogin(differentCookieNameServletPage, bburkeUser, testRealmSAMLPostLoginPage, "principal=bburke");

        Assert.assertThat(driver.manage().getCookieNamed("DIFFERENT_SESSION_ID"), notNullValue());
        Assert.assertThat(driver.manage().getCookieNamed("JSESSIONID"), nullValue());

        salesPost2ServletPage.logout();
        checkLoggedOut(differentCookieNameServletPage, testRealmSAMLPostLoginPage);
    }

    @Test
    /* KEYCLOAK-4980 */
    public void testAutodetectBearerOnly() throws Exception {
        Client client = ClientBuilder.newClient();

        // Do not redirect client to login page if it's an XHR
        WebTarget target = client.target(salesPostAutodetectServletPage.toString() + "/");
        Response response = target.request().header("X-Requested-With", "XMLHttpRequest").get();
        Assert.assertEquals(401, response.getStatus());
        response.close();

        // Do not redirect client to login page if it's a partial Faces request
        response = target.request().header("Faces-Request", "partial/ajax").get();
        Assert.assertEquals(401, response.getStatus());
        response.close();

        // Do not redirect client to login page if it's a SOAP request
        response = target.request().header("SOAPAction", "").get();
        Assert.assertEquals(401, response.getStatus());
        response.close();

        // Do not redirect client to login page if Accept header is missing
        response = target.request().get();
        Assert.assertEquals(401, response.getStatus());
        response.close();

        // Do not redirect client to login page if client does not understand HTML reponses
        response = target.request().header(HttpHeaders.ACCEPT, "application/json,text/xml").get();
        Assert.assertEquals(401, response.getStatus());
        response.close();

        // Redirect client to login page if it's not an XHR
        response = target.request().header("X-Requested-With", "Dont-Know").header(HttpHeaders.ACCEPT, "*/*").get();
        Assert.assertEquals(200, response.getStatus());
        response.close();

        // Redirect client to login page if client explicitely understands HTML responses
        response = target.request().header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9").get();
        Assert.assertEquals(200, response.getStatus());
        response.close();

        // Redirect client to login page if client understands all response types
        response = target.request().header(HttpHeaders.ACCEPT, "*/*").get();
        Assert.assertEquals(200, response.getStatus());
        response.close();
        client.close();
    }

    @Test
    public void testSuccessfulEcpFlow() throws Exception {
        Response authnRequestResponse = ClientBuilder.newClient().target(ecpSPPage.toString()).request()
                .header("Accept", "text/html; application/vnd.paos+xml")
                .header("PAOS", "ver='urn:liberty:paos:2003-08' ;'urn:oasis:names:tc:SAML:2.0:profiles:SSO:ecp'")
                .get();

        SOAPMessage authnRequestMessage = MessageFactory.newInstance().createMessage(null, new ByteArrayInputStream(authnRequestResponse.readEntity(byte[].class)));

        //printDocument(authnRequestMessage.getSOAPPart().getContent(), System.out);

        Iterator<SOAPHeaderElement> it = authnRequestMessage.getSOAPHeader().<SOAPHeaderElement>getChildElements(new QName("urn:oasis:names:tc:SAML:2.0:profiles:SSO:ecp", "Request"));
        SOAPHeaderElement ecpRequestHeader = it.next();
        NodeList idpList = ecpRequestHeader.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:protocol", "IDPList");

        Assert.assertThat("No IDPList returned from Service Provider", idpList.getLength(), is(1));

        NodeList idpEntries = idpList.item(0).getChildNodes();

        Assert.assertThat("No IDPEntry returned from Service Provider", idpEntries.getLength(), is(1));

        String singleSignOnService = null;

        for (int i = 0; i < idpEntries.getLength(); i++) {
            Node item = idpEntries.item(i);
            NamedNodeMap attributes = item.getAttributes();
            Node location = attributes.getNamedItem("Loc");

            singleSignOnService = location.getNodeValue();
        }

        Assert.assertThat("Could not obtain SSO Service URL", singleSignOnService, notNullValue());

        Document authenticationRequest = authnRequestMessage.getSOAPBody().getFirstChild().getOwnerDocument();
        String username = "pedroigor";
        String password = "password";
        String pair = username + ":" + password;
        String authHeader = "Basic " + Base64.encodeBytes(pair.getBytes());

        Response authenticationResponse = ClientBuilder.newClient().target(singleSignOnService).request()
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .post(Entity.entity(DocumentUtil.asString(authenticationRequest), "text/xml"));

        Assert.assertThat(authenticationResponse.getStatus(), is(OK.getStatusCode()));

        SOAPMessage responseMessage  = MessageFactory.newInstance().createMessage(null, new ByteArrayInputStream(authenticationResponse.readEntity(byte[].class)));

        //printDocument(responseMessage.getSOAPPart().getContent(), System.out);

        SOAPHeader responseMessageHeaders = responseMessage.getSOAPHeader();

        NodeList ecpResponse = responseMessageHeaders.getElementsByTagNameNS(JBossSAMLURIConstants.ECP_PROFILE.get(), JBossSAMLConstants.RESPONSE__ECP.get());

        Assert.assertThat("No ECP Response", ecpResponse.getLength(), is(1));

        Node samlResponse = responseMessage.getSOAPBody().getFirstChild();

        Assert.assertThat(samlResponse, notNullValue());

        ResponseType responseType = (ResponseType) SAMLParser.getInstance().parse(samlResponse);
        StatusCodeType statusCode = responseType.getStatus().getStatusCode();

        Assert.assertThat(statusCode.getValue().toString(), is(JBossSAMLURIConstants.STATUS_SUCCESS.get()));
        Assert.assertThat(responseType.getDestination(), is(ecpSPPage.toString() + "/"));
        Assert.assertThat(responseType.getSignature(), notNullValue());
        Assert.assertThat(responseType.getAssertions().size(), is(1));

        SOAPMessage samlResponseRequest = MessageFactory.newInstance().createMessage();

        samlResponseRequest.getSOAPBody().addDocument(responseMessage.getSOAPBody().extractContentAsDocument());

        ByteArrayOutputStream os = new ByteArrayOutputStream();

        samlResponseRequest.writeTo(os);

        Response serviceProviderFinalResponse = ClientBuilder.newClient().target(responseType.getDestination()).request()
                .post(Entity.entity(os.toByteArray(), "application/vnd.paos+xml"));

        Map<String, NewCookie> cookies = serviceProviderFinalResponse.getCookies();

        Invocation.Builder resourceRequest = ClientBuilder.newClient().target(responseType.getDestination()).request();

        for (NewCookie cookie : cookies.values()) {
            resourceRequest.cookie(cookie);
        }

        Response resourceResponse = resourceRequest.get();
        Assert.assertThat(resourceResponse.readEntity(String.class), containsString("pedroigor"));
    }

    @Test
    public void testInvalidCredentialsEcpFlow() throws Exception {
        Response authnRequestResponse = ClientBuilder.newClient().target(ecpSPPage.toString()).request()
                .header("Accept", "text/html; application/vnd.paos+xml")
                .header("PAOS", "ver='urn:liberty:paos:2003-08' ;'urn:oasis:names:tc:SAML:2.0:profiles:SSO:ecp'")
                .get();

        SOAPMessage authnRequestMessage = MessageFactory.newInstance().createMessage(null, new ByteArrayInputStream(authnRequestResponse.readEntity(byte[].class)));
        Iterator<SOAPHeaderElement> it = authnRequestMessage.getSOAPHeader().<SOAPHeaderElement>getChildElements(new QName("urn:liberty:paos:2003-08", "Request"));

        it.next();

        it = authnRequestMessage.getSOAPHeader().<SOAPHeaderElement>getChildElements(new QName("urn:oasis:names:tc:SAML:2.0:profiles:SSO:ecp", "Request"));
        SOAPHeaderElement ecpRequestHeader = it.next();
        NodeList idpList = ecpRequestHeader.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:protocol", "IDPList");

        Assert.assertThat("No IDPList returned from Service Provider", idpList.getLength(), is(1));

        NodeList idpEntries = idpList.item(0).getChildNodes();

        Assert.assertThat("No IDPEntry returned from Service Provider", idpEntries.getLength(), is(1));

        String singleSignOnService = null;

        for (int i = 0; i < idpEntries.getLength(); i++) {
            Node item = idpEntries.item(i);
            NamedNodeMap attributes = item.getAttributes();
            Node location = attributes.getNamedItem("Loc");

            singleSignOnService = location.getNodeValue();
        }

        Assert.assertThat("Could not obtain SSO Service URL", singleSignOnService, notNullValue());

        Document authenticationRequest = authnRequestMessage.getSOAPBody().getFirstChild().getOwnerDocument();
        String username = "pedroigor";
        String password = "baspassword";
        String pair = username + ":" + password;
        String authHeader = "Basic " + Base64.encodeBytes(pair.getBytes());

        Response authenticationResponse = ClientBuilder.newClient().target(singleSignOnService).request()
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .post(Entity.entity(DocumentUtil.asString(authenticationRequest), "application/soap+xml"));

        Assert.assertThat(authenticationResponse.getStatus(), is(OK.getStatusCode()));

        SOAPMessage responseMessage  = MessageFactory.newInstance().createMessage(null, new ByteArrayInputStream(authenticationResponse.readEntity(byte[].class)));
        Node samlResponse = responseMessage.getSOAPBody().getFirstChild();

        Assert.assertThat(samlResponse, notNullValue());

        StatusResponseType responseType = (StatusResponseType) SAMLParser.getInstance().parse(samlResponse);
        StatusCodeType statusCode = responseType.getStatus().getStatusCode();

        Assert.assertThat(statusCode.getStatusCode().getValue().toString(), is(not(JBossSAMLURIConstants.STATUS_SUCCESS.get())));
    }

    public static void printDocument(Source doc, OutputStream out) throws IOException, TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        transformer.transform(doc,
                new StreamResult(new OutputStreamWriter(out, "UTF-8")));
    }

    private URI getAuthServerSamlEndpoint(String realm) throws IllegalArgumentException, UriBuilderException {
        return RealmsResource
                .protocolUrl(UriBuilder.fromUri(getAuthServerRoot()))
                .build(realm, SamlProtocol.LOGIN_PROTOCOL);
    }

    private URI getAppServerSamlEndpoint(SAMLServlet page) throws IllegalArgumentException, UriBuilderException {
        return UriBuilder.fromPath(page.toString()).path("/saml").build();
    }

    private void validateXMLWithSchema(String xml, String schemaFileName) throws SAXException, IOException {
        URL schemaFile = getClass().getResource(schemaFileName);

        Source xmlFile = new StreamSource(new ByteArrayInputStream(xml.getBytes()), xml);
        SchemaFactory schemaFactory = SchemaFactory
                .newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = schemaFactory.newSchema(schemaFile);
        Validator validator = schema.newValidator();
        try {
            validator.validate(xmlFile);
            System.out.println(xmlFile.getSystemId() + " is valid");
        } catch (SAXException e) {
            System.out.println(xmlFile.getSystemId() + " is NOT valid");
            System.out.println("Reason: " + e.getLocalizedMessage());
            Assert.fail();
        }
    }

    private void createProtocolMapper(ProtocolMappersResource resource, String name, String protocol, String protocolMapper, Map<String, String> config) {
        ProtocolMapperRepresentation representation = new ProtocolMapperRepresentation();
        representation.setName(name);
        representation.setProtocol(protocol);
        representation.setProtocolMapper(protocolMapper);
        representation.setConfig(config);
        resource.createMapper(representation);
    }

    private void setRolesToCheck(String roles) {
        employee2ServletPage.navigateTo();
        assertCurrentUrlStartsWith(testRealmSAMLPostLoginPage);
        testRealmSAMLPostLoginPage.form().login(bburkeUser);
        driver.navigate().to(employee2ServletPage.toString() + "/setCheckRoles?roles=" + roles);
        employee2ServletPage.logout();
    }

    private void assertOnForbiddenPage() {
        waitUntilElement(By.xpath("//body")).is().present();

        //Different 403 status page on EAP and Wildfly
        Assert.assertTrue(driver.getPageSource().contains("Forbidden")
                || driver.getPageSource().contains(FORBIDDEN_TEXT)
                || driver.getPageSource().contains(WEBSPHERE_FORBIDDEN_TEXT)); // WebSphere
    }
}
