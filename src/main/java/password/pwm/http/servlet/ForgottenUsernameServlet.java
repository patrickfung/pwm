/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2016 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.http.servlet;

import password.pwm.PwmApplication;
import password.pwm.PwmConstants;
import password.pwm.bean.EmailItemBean;
import password.pwm.bean.LocalSessionStateBean;
import password.pwm.bean.SessionLabel;
import password.pwm.bean.SmsItemBean;
import password.pwm.bean.UserIdentity;
import password.pwm.bean.UserInfoBean;
import password.pwm.config.Configuration;
import password.pwm.config.FormConfiguration;
import password.pwm.config.FormUtility;
import password.pwm.config.PwmSetting;
import password.pwm.config.option.MessageSendMethod;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmOperationalException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.http.HttpMethod;
import password.pwm.http.PwmRequest;
import password.pwm.http.PwmSession;
import password.pwm.ldap.LdapUserDataReader;
import password.pwm.ldap.UserDataReader;
import password.pwm.ldap.UserSearchEngine;
import password.pwm.ldap.UserStatusReader;
import password.pwm.svc.stats.Statistic;
import password.pwm.util.CaptchaUtility;
import password.pwm.util.java.JavaHelper;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.macro.MacroMachine;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@WebServlet(
        name="ForgottenUsernameServlet",
        urlPatterns = {
                PwmConstants.URL_PREFIX_PUBLIC + "/forgottenusername",
                PwmConstants.URL_PREFIX_PUBLIC + "/ForgottenUsername",

        }
)
public class ForgottenUsernameServlet extends AbstractPwmServlet {
    private static final PwmLogger LOGGER = PwmLogger.forClass(ForgottenUsernameServlet.class);

    public enum ForgottenUsernameAction implements AbstractPwmServlet.ProcessAction {
        search,
        ;

        public Collection<HttpMethod> permittedMethods()
        {
            return Collections.singletonList(HttpMethod.POST);
        }
    }

    protected ForgottenUsernameAction readProcessAction(final PwmRequest request)
            throws PwmUnrecoverableException
    {
        try {
            return ForgottenUsernameAction.valueOf(request.readParameterAsString(PwmConstants.PARAM_ACTION_REQUEST));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    protected void processAction(final PwmRequest pwmRequest)
            throws ServletException, IOException, PwmUnrecoverableException
    {
        final Configuration config = pwmRequest.getConfig();

        if (!config.readSettingAsBoolean(PwmSetting.FORGOTTEN_USERNAME_ENABLE)) {
            pwmRequest.respondWithError(PwmError.ERROR_SERVICE_NOT_AVAILABLE.toInfo());
            return;
        }

        final ForgottenUsernameAction action = readProcessAction(pwmRequest);

        if (action != null) {
            pwmRequest.validatePwmFormID();
            switch (action) {
                case search:
                    handleSearchRequest(pwmRequest);
                    return;

                default:
                    JavaHelper.unhandledSwitchStatement(action);
            }
        }

        forwardToFormJsp(pwmRequest);
    }

    public void handleSearchRequest(
            final PwmRequest pwmRequest
    )
            throws PwmUnrecoverableException, IOException, ServletException
    {
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final PwmSession pwmSession = pwmRequest.getPwmSession();
        final LocalSessionStateBean ssBean = pwmSession.getSessionStateBean();

        if (!CaptchaUtility.verifyReCaptcha(pwmRequest)) {
            final ErrorInformation errorInfo = new ErrorInformation(PwmError.ERROR_BAD_CAPTCHA_RESPONSE);
            LOGGER.debug(pwmRequest, errorInfo);
            pwmRequest.setResponseError(errorInfo);
            forwardToFormJsp(pwmRequest);
            return;
        }

        final String contextParam = pwmRequest.readParameterAsString(PwmConstants.PARAM_CONTEXT);
        final String ldapProfile = pwmRequest.readParameterAsString(PwmConstants.PARAM_LDAP_PROFILE);

        final List<FormConfiguration> forgottenUsernameForm = pwmApplication.getConfig().readSettingAsForm(PwmSetting.FORGOTTEN_USERNAME_FORM);

        //read the values from the request
        Map<FormConfiguration, String> formValues = new HashMap<>();
        try {
            formValues = FormUtility.readFormValuesFromRequest(pwmRequest,
                    forgottenUsernameForm, ssBean.getLocale());

            // check for intruder search
            pwmApplication.getIntruderManager().convenience().checkAttributes(formValues);

            // see if the values meet the configured form requirements.
            FormUtility.validateFormValues(pwmRequest.getConfig(), formValues, ssBean.getLocale());

            final String searchFilter;
            {
                final String configuredSearchFilter = pwmApplication.getConfig().readSettingAsString(PwmSetting.FORGOTTEN_USERNAME_SEARCH_FILTER);
                if (configuredSearchFilter == null || configuredSearchFilter.isEmpty()) {
                    searchFilter = FormUtility.ldapSearchFilterForForm(pwmApplication, forgottenUsernameForm);
                    LOGGER.trace(pwmSession,"auto generated ldap search filter: " + searchFilter);
                } else {
                    searchFilter = configuredSearchFilter;
                }
            }

            final UserIdentity userIdentity;
            {
                final UserSearchEngine userSearchEngine = new UserSearchEngine(pwmApplication, pwmSession.getLabel());
                final UserSearchEngine.SearchConfiguration searchConfiguration = new UserSearchEngine.SearchConfiguration();
                searchConfiguration.setFilter(searchFilter);
                searchConfiguration.setFormValues(formValues);
                searchConfiguration.setLdapProfile(ldapProfile);
                searchConfiguration.setContexts(Collections.singletonList(contextParam));
                userIdentity = userSearchEngine.performSingleUserSearch(searchConfiguration);
            }

            if (userIdentity == null) {
                pwmApplication.getIntruderManager().convenience().markAddressAndSession(pwmSession);
                pwmApplication.getStatisticsManager().incrementValue(Statistic.FORGOTTEN_USERNAME_FAILURES);
                pwmRequest.setResponseError(PwmError.ERROR_CANT_MATCH_USER.toInfo());
                forwardToFormJsp(pwmRequest);
                return;
            }

            // make sure the user isn't locked.
            pwmApplication.getIntruderManager().convenience().checkUserIdentity(userIdentity);

            final UserStatusReader userStatusReader = new UserStatusReader(pwmApplication, pwmSession.getLabel());
            final UserInfoBean forgottenUserInfo = userStatusReader.populateUserInfoBean(pwmRequest.getLocale(), userIdentity);

            // send username
            sendUsername(pwmApplication, pwmSession, forgottenUserInfo);

            pwmApplication.getIntruderManager().convenience().clearAddressAndSession(pwmSession);
            pwmApplication.getIntruderManager().convenience().clearAttributes(formValues);

            pwmApplication.getStatisticsManager().incrementValue(Statistic.FORGOTTEN_USERNAME_SUCCESSES);

            // redirect user to success page.
            forwardToCompletePage(pwmRequest, userIdentity);
            return;

        } catch (PwmOperationalException e) {
            final ErrorInformation errorInfo;
            errorInfo = e.getError() == PwmError.ERROR_UNKNOWN
                    ? new ErrorInformation(PwmError.ERROR_CANT_MATCH_USER,e.getErrorInformation().getDetailedErrorMsg(),
                    e.getErrorInformation().getFieldValues())
                    : e.getErrorInformation();
            pwmRequest.setResponseError(errorInfo);
            pwmApplication.getIntruderManager().convenience().markAddressAndSession(pwmSession);
            pwmApplication.getIntruderManager().convenience().markAttributes(formValues, pwmSession);
        }

        pwmApplication.getStatisticsManager().incrementValue(Statistic.FORGOTTEN_USERNAME_FAILURES);
        forwardToFormJsp(pwmRequest);
    }


    private void sendUsername(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final UserInfoBean forgottenUserInfo
    )
            throws PwmOperationalException, PwmUnrecoverableException
    {
        final Locale userLocale = pwmSession.getSessionStateBean().getLocale();
        final Configuration configuration = pwmApplication.getConfig();
        final MessageSendMethod messageSendMethod = configuration.readSettingAsEnum(PwmSetting.FORGOTTEN_USERNAME_SEND_USERNAME_METHOD, MessageSendMethod.class);
        final EmailItemBean emailItemBean = configuration.readSettingAsEmail(PwmSetting.EMAIL_SEND_USERNAME, userLocale);
        final String smsMessage = configuration.readSettingAsLocalizedString(PwmSetting.SMS_FORGOTTEN_USERNAME_TEXT, userLocale);

        if (messageSendMethod == null || messageSendMethod == MessageSendMethod.NONE) {
            return;
        }

        sendMessageViaMethod(
                pwmApplication,
                pwmSession.getLabel(),
                forgottenUserInfo,
                messageSendMethod,
                emailItemBean,
                smsMessage
        );
    }


    private static void sendMessageViaMethod(
            final PwmApplication pwmApplication,
            final SessionLabel sessionLabel,
            final UserInfoBean userInfoBean,
            final MessageSendMethod messageSendMethod,
            final EmailItemBean emailItemBean,
            final String smsMessage
    )
            throws PwmOperationalException, PwmUnrecoverableException
    {
        if (pwmApplication == null) {
            throw new IllegalArgumentException("pwmApplication can not be null");
        }

        if (userInfoBean == null) {
            throw new IllegalArgumentException("userInfoBean can not be null");
        }

        ErrorInformation error = null;
        switch (messageSendMethod) {
            case NONE:
                break;

            case BOTH:
                // Send both email and SMS, success if one of both succeeds
                final ErrorInformation err1 = sendEmailViaMethod(pwmApplication, sessionLabel, userInfoBean, emailItemBean);
                final ErrorInformation err2 = sendSmsViaMethod(pwmApplication, sessionLabel, userInfoBean, smsMessage);
                if (err1 != null) {
                    error = err1;
                } else if (err2 != null) {
                    error = err2;
                }
                break;
            case EMAILFIRST:
                // Send email first, try SMS if email is not available
                error = sendEmailViaMethod(pwmApplication, sessionLabel, userInfoBean, emailItemBean);
                if (error != null) {
                    error = sendSmsViaMethod(pwmApplication, sessionLabel, userInfoBean, smsMessage);
                }
                break;
            case SMSFIRST:
                // Send SMS first, try email if SMS is not available
                error = sendSmsViaMethod(pwmApplication, sessionLabel, userInfoBean, smsMessage);
                if (error != null) {
                    error = sendEmailViaMethod(pwmApplication, sessionLabel, userInfoBean, emailItemBean);
                }
                break;
            case SMSONLY:
                // Only try SMS
                error = sendSmsViaMethod(pwmApplication, sessionLabel, userInfoBean, smsMessage);
                break;
            case EMAILONLY:
            default:
                // Only try email
                error = sendEmailViaMethod(pwmApplication, sessionLabel, userInfoBean, emailItemBean);
                break;
        }
        if (error != null) {
            throw new PwmOperationalException(error);
        }
    }

    private static ErrorInformation sendSmsViaMethod(
            final PwmApplication pwmApplication,
            final SessionLabel sessionLabel,
            final UserInfoBean userInfoBean,
            final String smsMessage
    )
            throws PwmOperationalException, PwmUnrecoverableException
    {
        final String toNumber = userInfoBean.getUserSmsNumber();
        if (toNumber == null || toNumber.length() < 1) {
            final String errorMsg = String.format("unable to send new password email for '%s'; no SMS number available in ldap", userInfoBean.getUserIdentity());
            return new ErrorInformation(PwmError.ERROR_UNKNOWN, errorMsg);
        }

        final UserDataReader userDataReader = LdapUserDataReader.appProxiedReader(pwmApplication, userInfoBean.getUserIdentity());
        final MacroMachine macroMachine = new MacroMachine(pwmApplication, sessionLabel, userInfoBean, null, userDataReader);

        final SmsItemBean smsItem = new SmsItemBean(toNumber, smsMessage);
        pwmApplication.sendSmsUsingQueue(smsItem, macroMachine);
        return null;
    }

    private static ErrorInformation sendEmailViaMethod(
            final PwmApplication pwmApplication,
            final SessionLabel sessionLabel,
            final UserInfoBean userInfoBean,
            final EmailItemBean emailItemBean
    )
            throws PwmUnrecoverableException
    {
        if (emailItemBean == null) {
            final String errorMsg = "emailItemBean is null";
            return new ErrorInformation(PwmError.ERROR_UNKNOWN, errorMsg);
        }

        final UserDataReader userDataReader = LdapUserDataReader.appProxiedReader(pwmApplication, userInfoBean.getUserIdentity());
        final MacroMachine macroMachine = new MacroMachine(pwmApplication, sessionLabel, userInfoBean, null, userDataReader);

        pwmApplication.getEmailQueue().submitEmail(emailItemBean, userInfoBean, macroMachine);

        return null;
    }

    private void forwardToFormJsp(final PwmRequest pwmRequest)
            throws ServletException, PwmUnrecoverableException, IOException
    {
        pwmRequest.addFormInfoToRequestAttr(PwmSetting.FORGOTTEN_USERNAME_FORM,false,false);
        pwmRequest.forwardToJsp(PwmConstants.JspUrl.FORGOTTEN_USERNAME);
    }

    private static void forwardToCompletePage(final PwmRequest pwmRequest, final UserIdentity userIdentity)
            throws PwmUnrecoverableException, ServletException, IOException
    {
        final Locale locale = pwmRequest.getLocale();
        final String completeMessage = pwmRequest.getConfig().readSettingAsLocalizedString(PwmSetting.FORGOTTEN_USERNAME_MESSAGE,locale);
        final MacroMachine macroMachine = MacroMachine.forUser(pwmRequest.getPwmApplication(), pwmRequest.getLocale(), pwmRequest.getSessionLabel(), userIdentity);
        final String expandedText = macroMachine.expandMacros(completeMessage);
        pwmRequest.setAttribute(PwmRequest.Attribute.CompleteText, expandedText);
        pwmRequest.forwardToJsp(PwmConstants.JspUrl.FORGOTTEN_USERNAME_COMPLETE);
    }

}
