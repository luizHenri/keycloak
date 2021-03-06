package org.keycloak.testsuite.broker;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.jboss.arquillian.drone.api.annotation.Drone;
import org.junit.Test;

import org.keycloak.admin.client.resource.IdentityProviderResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.IdentityProviderMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.Assert;
import org.keycloak.testsuite.pages.ConsentPage;
import org.keycloak.testsuite.util.*;

import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.keycloak.testsuite.admin.ApiUtil.createUserWithAdminClient;
import static org.keycloak.testsuite.admin.ApiUtil.removeUserByUsername;
import static org.keycloak.testsuite.admin.ApiUtil.resetUserPassword;
import static org.keycloak.testsuite.broker.BrokerTestConstants.USER_EMAIL;
import static org.keycloak.testsuite.util.MailAssert.assertEmailAndGetUrl;

import org.jboss.arquillian.graphene.page.Page;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import javax.ws.rs.core.Response;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.keycloak.testsuite.broker.BrokerTestTools.*;

public abstract class AbstractBrokerTest extends AbstractInitializedBaseBrokerTest {

    public static final String ROLE_USER = "user";
    public static final String ROLE_MANAGER = "manager";
    public static final String ROLE_FRIENDLY_MANAGER = "friendly-manager";

    @Drone
    @SecondBrowser
    protected WebDriver driver2;

    @Test
    public void testLogInAsUserInIDP() {
        loginUser();

        testSingleLogout();
    }

    protected void loginUser() {
        driver.navigate().to(getAccountUrl(bc.consumerRealmName()));

        log.debug("Clicking social " + bc.getIDPAlias());
        accountLoginPage.clickSocial(bc.getIDPAlias());

        waitForPage(driver, "log in to", true);

        Assert.assertTrue("Driver should be on the provider realm page right now",
          driver.getCurrentUrl().contains("/auth/realms/" + bc.providerRealmName() + "/"));

        log.debug("Logging in");
        accountLoginPage.login(bc.getUserLogin(), bc.getUserPassword());

        waitForPage(driver, "update account information", false);

        updateAccountInformationPage.assertCurrent();
        Assert.assertTrue("We must be on correct realm right now",
          driver.getCurrentUrl().contains("/auth/realms/" + bc.consumerRealmName() + "/"));

        log.debug("Updating info on updateAccount page");
        updateAccountInformationPage.updateAccountInformation(bc.getUserLogin(), bc.getUserEmail(), "Firstname", "Lastname");

        UsersResource consumerUsers = adminClient.realm(bc.consumerRealmName()).users();

        int userCount = consumerUsers.count();
        Assert.assertTrue("There must be at least one user", userCount > 0);

        List<UserRepresentation> users = consumerUsers.search("", 0, userCount);

        boolean isUserFound = false;
        for (UserRepresentation user : users) {
            if (user.getUsername().equals(bc.getUserLogin()) && user.getEmail().equals(bc.getUserEmail())) {
                isUserFound = true;
                break;
            }
        }

        Assert.assertTrue("There must be user " + bc.getUserLogin() + " in realm " + bc.consumerRealmName(),
          isUserFound);
    }

    @Test
    public void loginWithExistingUser() {
        testLogInAsUserInIDP();

        Integer userCount = adminClient.realm(bc.consumerRealmName()).users().count();

        driver.navigate().to(getAccountUrl(bc.consumerRealmName()));

        log.debug("Clicking social " + bc.getIDPAlias());
        accountLoginPage.clickSocial(bc.getIDPAlias());

        waitForPage(driver, "log in to", true);

        Assert.assertTrue("Driver should be on the provider realm page right now", driver.getCurrentUrl().contains("/auth/realms/" + bc.providerRealmName() + "/"));

        accountLoginPage.login(bc.getUserLogin(), bc.getUserPassword());

        assertEquals(accountPage.buildUri().toASCIIString().replace("master", "consumer") + "/", driver.getCurrentUrl());

        assertEquals(userCount, adminClient.realm(bc.consumerRealmName()).users().count());
    }
    
    // KEYCLOAK-2957
    @Test
    public void testLinkAccountWithEmailVerified() {
        //start mail server
        MailServer.start();
        MailServer.createEmailAccount(USER_EMAIL, "password");
        
        try {
            configureSMPTServer();

            //create user on consumer's site who should be linked later
            String linkedUserId = createUser("consumer");
        
            //test
            driver.navigate().to(getAccountUrl(bc.consumerRealmName()));

            log.debug("Clicking social " + bc.getIDPAlias());
            accountLoginPage.clickSocial(bc.getIDPAlias());

            waitForPage(driver, "log in to", true);

            Assert.assertTrue("Driver should be on the provider realm page right now",
                    driver.getCurrentUrl().contains("/auth/realms/" + bc.providerRealmName() + "/"));

            log.debug("Logging in");
            accountLoginPage.login(bc.getUserLogin(), bc.getUserPassword());

            waitForPage(driver, "update account information", false);

            Assert.assertTrue(updateAccountInformationPage.isCurrent());
            Assert.assertTrue("We must be on correct realm right now",
                    driver.getCurrentUrl().contains("/auth/realms/" + bc.consumerRealmName() + "/"));

            log.debug("Updating info on updateAccount page");
            updateAccountInformationPage.updateAccountInformation("Firstname", "Lastname");

            //link account by email
            waitForPage(driver, "account already exists", false);
            idpConfirmLinkPage.clickLinkAccount();
            
            String url = assertEmailAndGetUrl(MailServerConfiguration.FROM, USER_EMAIL, 
                    "Someone wants to link your ", false);

            log.info("navigating to url from email: " + url);
            driver.navigate().to(url);

            //test if user is logged in
            assertEquals(accountPage.buildUri().toASCIIString().replace("master", "consumer") + "/", driver.getCurrentUrl());
            
            //test if the user has verified email
            assertTrue(adminClient.realm(bc.consumerRealmName()).users().get(linkedUserId).toRepresentation().isEmailVerified());
        } finally {
            removeUserByUsername(adminClient.realm(bc.consumerRealmName()), "consumer");
            // stop mail server
            MailServer.stop();
        }
    }

    @Test
    public void testVerifyEmailInNewBrowserWithPreserveClient() {
        //start mail server
        MailServer.start();
        MailServer.createEmailAccount(USER_EMAIL, "password");

        try {
            configureSMPTServer();

            //create user on consumer's site who should be linked later
            String linkedUserId = createUser("consumer");

            driver.navigate().to(getLoginUrl(bc.consumerRealmName(), "broker-app"));

            log.debug("Clicking social " + bc.getIDPAlias());
            accountLoginPage.clickSocial(bc.getIDPAlias());

            waitForPage(driver, "log in to", true);

            Assert.assertTrue("Driver should be on the provider realm page right now",
                    driver.getCurrentUrl().contains("/auth/realms/" + bc.providerRealmName() + "/"));

            log.debug("Logging in");
            accountLoginPage.login(bc.getUserLogin(), bc.getUserPassword());

            waitForPage(driver, "update account information", false);

            Assert.assertTrue(updateAccountInformationPage.isCurrent());
            Assert.assertTrue("We must be on correct realm right now",
                    driver.getCurrentUrl().contains("/auth/realms/" + bc.consumerRealmName() + "/"));

            log.debug("Updating info on updateAccount page");
            updateAccountInformationPage.updateAccountInformation("Firstname", "Lastname");

            //link account by email
            waitForPage(driver, "account already exists", false);
            idpConfirmLinkPage.clickLinkAccount();

            String url = assertEmailAndGetUrl(MailServerConfiguration.FROM, USER_EMAIL,
                    "Someone wants to link your ", false);

            log.info("navigating to url from email in second browser: " + url);

            // navigate to url in the second browser
            driver2.navigate().to(url);

            final WebElement proceedLink = driver2.findElement(By.linkText("» Click here to proceed"));
            MatcherAssert.assertThat(proceedLink, Matchers.notNullValue());

            // check if the initial client is preserved
            String link = proceedLink.getAttribute("href");
            MatcherAssert.assertThat(link, Matchers.containsString("client_id=broker-app"));
            proceedLink.click();

            assertThat(driver2.getPageSource(), Matchers.containsString("You successfully verified your email. Please go back to your original browser and continue there with the login."));

            //test if the user has verified email
            assertTrue(adminClient.realm(bc.consumerRealmName()).users().get(linkedUserId).toRepresentation().isEmailVerified());
        } finally {
            removeUserByUsername(adminClient.realm(bc.consumerRealmName()), "consumer");
            // stop mail server
            MailServer.stop();
        }
    }

    // KEYCLOAK-3267
    @Test
    public void loginWithExistingUserWithBruteForceEnabled() {
        adminClient.realm(bc.consumerRealmName()).update(RealmBuilder.create().bruteForceProtected(true).failureFactor(2).build());

        loginWithExistingUser();

        driver.navigate().to(getAccountPasswordUrl(bc.consumerRealmName()));

        accountPasswordPage.changePassword("password", "password");

        logoutFromRealm(bc.providerRealmName());

        driver.navigate().to(getAccountUrl(bc.consumerRealmName()));

        try {
            waitForPage(driver, "log in to", true);
        } catch (TimeoutException e) {
            log.debug(driver.getTitle());
            log.debug(driver.getPageSource());
            Assert.fail("Timeout while waiting for login page");
        }

        for (int i = 0; i < 3; i++) {
            try {
                waitForElementEnabled(driver, "login");
            } catch (TimeoutException e) {
                Assert.fail("Timeout while waiting for login element enabled");
            }

            accountLoginPage.login(bc.getUserLogin(), "invalid");
        }

        assertEquals("Invalid username or password.", accountLoginPage.getError());

        accountLoginPage.clickSocial(bc.getIDPAlias());

        try {
            waitForPage(driver, "log in to", true);
        } catch (TimeoutException e) {
            log.debug(driver.getTitle());
            log.debug(driver.getPageSource());
            Assert.fail("Timeout while waiting for login page");
        }

        Assert.assertTrue("Driver should be on the provider realm page right now", driver.getCurrentUrl().contains("/auth/realms/" + bc.providerRealmName() + "/"));

        accountLoginPage.login(bc.getUserLogin(), bc.getUserPassword());

        assertEquals("Account is disabled, contact admin.", errorPage.getError());
    }

    @Page
    ConsentPage consentPage;

    // KEYCLOAK-4181
    @Test
    public void loginWithExistingUserWithErrorFromProviderIdP() {
        ClientRepresentation client = adminClient.realm(bc.providerRealmName())
          .clients()
          .findByClientId(bc.getIDPClientIdInProviderRealm(suiteContext))
          .get(0);

        adminClient.realm(bc.providerRealmName())
          .clients()
          .get(client.getId())
          .update(ClientBuilder.edit(client).consentRequired(true).build());

        driver.navigate().to(getAccountUrl(bc.consumerRealmName()));

        log.debug("Clicking social " + bc.getIDPAlias());
        accountLoginPage.clickSocial(bc.getIDPAlias());

        waitForPage(driver, "log in to", true);

        Assert.assertTrue("Driver should be on the provider realm page right now",
                driver.getCurrentUrl().contains("/auth/realms/" + bc.providerRealmName() + "/"));

        log.debug("Logging in");
        accountLoginPage.login(bc.getUserLogin(), bc.getUserPassword());

        driver.manage().timeouts().pageLoadTimeout(30, TimeUnit.MINUTES);

        waitForPage(driver, "grant access", false);
        consentPage.cancel();

        waitForPage(driver, "log in to", true);

        // Revert consentRequired
        adminClient.realm(bc.providerRealmName())
                .clients()
                .get(client.getId())
                .update(ClientBuilder.edit(client).consentRequired(false).build());

    }



    protected void testSingleLogout() {
        log.debug("Testing single log out");

        driver.navigate().to(getAccountUrl(bc.providerRealmName()));

        Assert.assertTrue("Should be logged in the account page", driver.getTitle().endsWith("Account Management"));

        logoutFromRealm(bc.providerRealmName());

        Assert.assertTrue("Should be on " + bc.providerRealmName() + " realm", driver.getCurrentUrl().contains("/auth/realms/" + bc.providerRealmName()));

        driver.navigate().to(getAccountUrl(bc.consumerRealmName()));

        Assert.assertTrue("Should be on " + bc.consumerRealmName() + " realm on login page",
                driver.getCurrentUrl().contains("/auth/realms/" + bc.consumerRealmName() + "/protocol/openid-connect/"));
    }

    protected void createRolesForRealm(String realm) {
        RoleRepresentation managerRole = new RoleRepresentation(ROLE_MANAGER,null, false);
        RoleRepresentation friendlyManagerRole = new RoleRepresentation(ROLE_FRIENDLY_MANAGER,null, false);
        RoleRepresentation userRole = new RoleRepresentation(ROLE_USER,null, false);

        adminClient.realm(realm).roles().create(managerRole);
        adminClient.realm(realm).roles().create(friendlyManagerRole);
        adminClient.realm(realm).roles().create(userRole);
    }

    protected void createRoleMappersForConsumerRealm() {
        log.debug("adding mappers to identity provider in realm " + bc.consumerRealmName());

        RealmResource realm = adminClient.realm(bc.consumerRealmName());

        IdentityProviderResource idpResource = realm.identityProviders().get(bc.getIDPAlias());
        for (IdentityProviderMapperRepresentation mapper : createIdentityProviderMappers()) {
            mapper.setIdentityProviderAlias(bc.getIDPAlias());
            Response resp = idpResource.addMapper(mapper);
            resp.close();
        }
    }

    protected abstract Iterable<IdentityProviderMapperRepresentation> createIdentityProviderMappers();

    // KEYCLOAK-3987
    @Test
    public void grantNewRoleFromToken() {
        createRolesForRealm(bc.providerRealmName());
        createRolesForRealm(bc.consumerRealmName());

        createRoleMappersForConsumerRealm();

        RoleRepresentation managerRole = adminClient.realm(bc.providerRealmName()).roles().get(ROLE_MANAGER).toRepresentation();
        RoleRepresentation userRole = adminClient.realm(bc.providerRealmName()).roles().get(ROLE_USER).toRepresentation();

        UserResource userResource = adminClient.realm(bc.providerRealmName()).users().get(userId);
        userResource.roles().realmLevel().add(Collections.singletonList(managerRole));

        logInAsUserInIDPForFirstTime();

        Set<String> currentRoles = userResource.roles().realmLevel().listAll().stream()
          .map(RoleRepresentation::getName)
          .collect(Collectors.toSet());

        assertThat(currentRoles, hasItems(ROLE_MANAGER));
        assertThat(currentRoles, not(hasItems(ROLE_USER)));

        logoutFromRealm(bc.consumerRealmName());


        userResource.roles().realmLevel().add(Collections.singletonList(userRole));

        logInAsUserInIDP();

        currentRoles = userResource.roles().realmLevel().listAll().stream()
          .map(RoleRepresentation::getName)
          .collect(Collectors.toSet());
        assertThat(currentRoles, hasItems(ROLE_MANAGER, ROLE_USER));

        logoutFromRealm(bc.providerRealmName());
        logoutFromRealm(bc.consumerRealmName());
    }


    // KEYCLOAK-4016
    @Test
    public void testExpiredCode() {
        driver.navigate().to(getAccountUrl(bc.consumerRealmName()));

        log.debug("Expire all browser cookies");
        driver.manage().deleteAllCookies();

        log.debug("Clicking social " + bc.getIDPAlias());
        accountLoginPage.clickSocial(bc.getIDPAlias());

        waitForPage(driver, "sorry", false);
        errorPage.assertCurrent();
        String link = errorPage.getBackToApplicationLink();
        Assert.assertTrue(link.endsWith("/auth/realms/consumer/account"));
    }

    private void configureSMPTServer() {
        RealmRepresentation master = adminClient.realm(bc.consumerRealmName()).toRepresentation();
        master.setSmtpServer(suiteContext.getSmtpServer());
        adminClient.realm(bc.consumerRealmName()).update(master);
    }

    private String createUser(String username) {
        UserRepresentation newUser = UserBuilder.create().username(username).email(USER_EMAIL).enabled(true).build();
        String userId = createUserWithAdminClient(adminClient.realm(bc.consumerRealmName()), newUser);
        resetUserPassword(adminClient.realm(bc.consumerRealmName()).users().get(userId), "password", false);
        return userId;
    }
}
