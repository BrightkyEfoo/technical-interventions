<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "password-commons.ftl" as passwords>

<@layout.registrationLayout displayRequiredFields=false displayMessage=!messagesPerField.existsError('totp', 'userLabel'); section>
    <#if section == "header">
        ${msg("loginTotpTitle")}
    <#elseif section == "form">
        <ol class="pf-v5-c-list pf-v5-u-mb-md">
            <li>
                ${msg("loginTotpStep1")}
                <ul>
                    <#list totp.supportedApplications as app>
                        <li>${msg(app)}</li>
                    </#list>
                </ul>
            </li>
            <li>
                <p>${msg("loginTotpStep2")}</p>
                <img id="kc-totp-secret-qr-code" width="246" height="246"
                     src="data:image/png;base64,${totp.totpSecretQrCode}" alt="QR code">
                <p class="pf-v5-u-mt-sm">${msg("loginTotpManualStep2")}</p>
                <code id="kc-totp-secret-key" dir="ltr">${totp.totpSecretEncoded}</code>
                <p>
                    ${msg("loginTotp." + totp.policy.type)} · ${totp.policy.getAlgorithmKey()}
                    · ${msg("loginTotpDigits")} ${totp.policy.digits}
                    <#if totp.policy.type == "totp">
                        · ${msg("loginTotpInterval")} ${totp.policy.period}
                    <#else>
                        · ${msg("loginTotpCounter")} ${totp.policy.initialCounter}
                    </#if>
                </p>
            </li>
            <li>${msg("loginTotpStep3")}</li>
        </ol>

        <form id="kc-totp-settings-form" class="${properties.kcFormClass}" action="${url.loginAction}" method="post">
            <@field.input name="totp" label=msg("authenticatorCode") required=true autocomplete="one-time-code" />
            <input name="totpSecret" type="hidden" value="${totp.totpSecret}">
            <@field.input name="userLabel" label=msg("loginTotpDeviceName") required=(totp.otpCredentials?size gt 0) />
            <@passwords.logoutOtherSessions/>
            <div class="${properties.kcFormActionGroupClass}">
                <button type="submit" id="saveTOTPBtn" class="${properties.kcButtonClass} ${properties.kcButtonPrimaryClass}">
                    ${msg("doSubmit")}
                </button>
                <#if isAppInitiatedAction??>
                    <button type="submit" name="cancel-aia" value="true" class="${properties.kcButtonClass} ${properties.kcButtonSecondaryClass}">
                        ${msg("doCancel")}
                    </button>
                </#if>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
