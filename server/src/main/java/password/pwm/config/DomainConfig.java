/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2020 The PWM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package password.pwm.config;

import password.pwm.AppProperty;
import password.pwm.PwmConstants;
import password.pwm.bean.DomainID;
import password.pwm.bean.EmailItemBean;
import password.pwm.bean.PrivateKeyCertificate;
import password.pwm.config.option.CertificateMatchingMode;
import password.pwm.config.option.DataStorageMethod;
import password.pwm.config.option.MessageSendMethod;
import password.pwm.config.option.TokenStorageMethod;
import password.pwm.config.profile.ActivateUserProfile;
import password.pwm.config.profile.ChallengeProfile;
import password.pwm.config.profile.ChangePasswordProfile;
import password.pwm.config.profile.EmailServerProfile;
import password.pwm.config.profile.ForgottenPasswordProfile;
import password.pwm.config.profile.HelpdeskProfile;
import password.pwm.config.profile.LdapProfile;
import password.pwm.config.profile.NewUserProfile;
import password.pwm.config.profile.PeopleSearchProfile;
import password.pwm.config.profile.Profile;
import password.pwm.config.profile.ProfileDefinition;
import password.pwm.config.profile.ProfileUtility;
import password.pwm.config.profile.PwmPasswordPolicy;
import password.pwm.config.profile.SetupOtpProfile;
import password.pwm.config.profile.UpdateProfileProfile;
import password.pwm.config.stored.StoredConfigItemKey;
import password.pwm.config.stored.StoredConfiguration;
import password.pwm.config.stored.StoredConfigurationUtil;
import password.pwm.config.value.FileValue;
import password.pwm.config.value.StoredValue;
import password.pwm.config.value.ValueTypeConverter;
import password.pwm.config.value.data.ActionConfiguration;
import password.pwm.config.value.data.FormConfiguration;
import password.pwm.config.value.data.NamedSecretData;
import password.pwm.config.value.data.RemoteWebServiceConfiguration;
import password.pwm.config.value.data.UserPermission;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.i18n.PwmLocaleBundle;
import password.pwm.util.PasswordData;
import password.pwm.util.i18n.LocaleHelper;
import password.pwm.util.java.LazySupplier;
import password.pwm.util.java.StringUtil;
import password.pwm.util.logging.PwmLogLevel;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.secure.PwmRandom;
import password.pwm.util.secure.PwmSecurityKey;
import password.pwm.util.secure.SecureService;

import java.lang.reflect.InvocationTargetException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author Jason D. Rivard
 */
public class DomainConfig
{
    private static final PwmLogger LOGGER = PwmLogger.forClass( DomainConfig.class );

    private final StoredConfiguration storedConfiguration;
    private final AppConfig appConfig;
    private final DomainID domainID;

    private final ConfigurationSuppliers configurationSuppliers = new ConfigurationSuppliers();

    private final DataCache dataCache = new DataCache();
    private final SettingReader settingReader;

    public DomainConfig( final AppConfig appConfig, final DomainID domainID )
    {
        this.appConfig = Objects.requireNonNull( appConfig );
        this.storedConfiguration = appConfig.getStoredConfiguration();
        this.domainID = Objects.requireNonNull( domainID );
        this.settingReader = new SettingReader( storedConfiguration, null, PwmConstants.DOMAIN_ID_PLACEHOLDER );
    }

    public AppConfig getAppConfig()
    {
        return appConfig;
    }

    public static void deprecatedSettingException( final PwmSetting pwmSetting, final String profile, final MessageSendMethod value )
    {
        if ( value != null && value.isDeprecated() )
        {
            final String msg = pwmSetting.toMenuLocationDebug( profile, PwmConstants.DEFAULT_LOCALE )
                    + " setting is using a no longer functional setting value: " + value;
            throw new IllegalStateException( msg );
        }
    }

    public List<FormConfiguration> readSettingAsForm( final PwmSetting setting )
    {
        return settingReader.readSettingAsForm( setting );
    }

    public List<UserPermission> readSettingAsUserPermission( final PwmSetting setting )
    {
        return settingReader.readSettingAsUserPermission( setting );
    }

    public Map<String, LdapProfile> getLdapProfiles( )
    {
        return configurationSuppliers.ldapProfilesSupplier.get();
    }

    public EmailItemBean readSettingAsEmail( final PwmSetting setting, final Locale locale )
    {
        final Map<Locale, EmailItemBean> availableLocaleMap = ValueTypeConverter.valueToLocalizedEmail( setting, readStoredValue( setting ) );
        final Locale matchedLocale = LocaleHelper.localeResolver( locale, availableLocaleMap.keySet() );
        return availableLocaleMap.get( matchedLocale );
    }

    public <E extends Enum<E>> E readSettingAsEnum( final PwmSetting setting, final Class<E> enumClass )
    {
        return settingReader.readSettingAsEnum( setting, enumClass );
    }

    public <E extends Enum<E>> Set<E> readSettingAsOptionList( final PwmSetting setting, final Class<E> enumClass )
    {
        return settingReader.readSettingAsOptionList( setting, enumClass );
    }

    public List<ActionConfiguration> readSettingAsAction( final PwmSetting setting )
    {
        return settingReader.readSettingAsAction( setting );
    }

    public List<String> readSettingAsLocalizedStringArray( final PwmSetting setting, final Locale locale )
    {
        return settingReader.readSettingAsLocalizedStringArray( setting, locale );
    }

    public String readSettingAsString( final PwmSetting setting )
    {
        return settingReader.readSettingAsString( setting );
    }

    public List<RemoteWebServiceConfiguration> readSettingAsRemoteWebService( final PwmSetting setting )
    {
        return settingReader.readSettingAsRemoteWebService( setting );
    }

    public PasswordData readSettingAsPassword( final PwmSetting setting )
    {
        return settingReader.readSettingAsPassword( setting );
    }

    public Map<String, NamedSecretData> readSettingAsNamedPasswords( final PwmSetting setting )
    {
        return settingReader.readSettingAsNamedPasswords( setting );
    }

    public Map<Locale, String> readLocalizedBundle( final PwmLocaleBundle className, final String keyName )
    {
        final String key = className + "-" + keyName;
        if ( dataCache.customText.containsKey( key ) )
        {
            return dataCache.customText.get( key );
        }


        final Map<String, String> storedValue = storedConfiguration.readLocaleBundleMap( className, keyName );
        if ( storedValue == null || storedValue.isEmpty() )
        {
            dataCache.customText.put( key, null );
            return null;
        }

        final Map<Locale, String> localizedMap = new LinkedHashMap<>();
        for ( final Map.Entry<String, String> entry : storedValue.entrySet() )
        {
            final String localeKey = entry.getKey();
            localizedMap.put( LocaleHelper.parseLocaleString( localeKey ), entry.getValue() );
        }

        dataCache.customText.put( key, localizedMap );
        return localizedMap;
    }

    public PwmLogLevel getEventLogLocalDBLevel( )
    {
        return readSettingAsEnum( PwmSetting.EVENTS_LOCALDB_LOG_LEVEL, PwmLogLevel.class );
    }

    public List<String> getChallengeProfileIDs( )
    {
        return StoredConfigurationUtil.profilesForSetting( PwmSetting.CHALLENGE_PROFILE_LIST, storedConfiguration );
    }

    public ChallengeProfile getChallengeProfile( final String profile, final Locale locale )
    {
        if ( !"".equals( profile ) && !getChallengeProfileIDs().contains( profile ) )
        {
            throw new IllegalArgumentException( "unknown challenge profileID specified: " + profile );
        }

        // challengeProfile challengeSet's are mutable (question text) and can not be cached.
        return ChallengeProfile.readChallengeProfileFromConfig( profile, locale, storedConfiguration );
    }

    public long readSettingAsLong( final PwmSetting setting )
    {
        return settingReader.readSettingAsLong( setting );
    }

    public PwmPasswordPolicy getPasswordPolicy( final String profile, final Locale locale )
    {
        return dataCache.cachedPasswordPolicy
                .computeIfAbsent( profile, s -> new HashMap<>() )
                .computeIfAbsent( locale, s -> PwmPasswordPolicy.createPwmPasswordPolicy( this, profile, locale ) );
    }

    public List<String> getPasswordProfileIDs( )
    {
        return StoredConfigurationUtil.profilesForSetting( PwmSetting.PASSWORD_PROFILE_LIST, storedConfiguration );
    }


    public List<String> readSettingAsStringArray( final PwmSetting setting )
    {
        return ValueTypeConverter.valueToStringArray( readStoredValue( setting ) );
    }

    public String readSettingAsLocalizedString( final PwmSetting setting, final Locale locale )
    {
        return ValueTypeConverter.valueToLocalizedString( readStoredValue( setting ), locale );
    }

    public boolean isDefaultValue( final PwmSetting pwmSetting )
    {
        return storedConfiguration.isDefaultValue( pwmSetting, null );
    }

    public boolean readSettingAsBoolean( final PwmSetting setting )
    {
        return ValueTypeConverter.valueToBoolean( readStoredValue( setting ) );
    }

    public Map<FileValue.FileInformation, FileValue.FileContent> readSettingAsFile( final PwmSetting setting )
    {
        return ValueTypeConverter.valueToFile( setting, readStoredValue( setting ) );
    }

    public List<X509Certificate> readSettingAsCertificate( final PwmSetting setting )
    {
        return ValueTypeConverter.valueToX509Certificates( setting, readStoredValue( setting ) );
    }

    public PrivateKeyCertificate readSettingAsPrivateKey( final PwmSetting setting )
    {
        return settingReader.readSettingAsPrivateKey( setting );
    }

    private PwmSecurityKey tempInstanceKey = null;

    public PwmSecurityKey getSecurityKey( ) throws PwmUnrecoverableException
    {
        return configurationSuppliers.pwmSecurityKey.call();
    }

    public List<DataStorageMethod> getResponseStorageLocations( final PwmSetting setting )
    {
        return getGenericStorageLocations( setting );
    }

    public List<DataStorageMethod> getOtpSecretStorageLocations( final PwmSetting setting )
    {
        return getGenericStorageLocations( setting );
    }

    private List<DataStorageMethod> getGenericStorageLocations( final PwmSetting setting )
    {
        final String input = readSettingAsString( setting );
        final List<DataStorageMethod> storageMethods = new ArrayList<>();
        for ( final String rawValue : input.split( "-" ) )
        {
            try
            {
                storageMethods.add( DataStorageMethod.valueOf( rawValue ) );
            }
            catch ( final IllegalArgumentException e )
            {
                LOGGER.error( () -> "unknown STORAGE_METHOD found: " + rawValue );
            }
        }
        return storageMethods;
    }

    public LdapProfile getDefaultLdapProfile( ) throws PwmUnrecoverableException
    {
        return getLdapProfiles().values().iterator().next();
    }


    public List<Locale> getKnownLocales( )
    {
        return getAppConfig().getKnownLocales();
    }

    public Map<Locale, String> getKnownLocaleFlagMap( )
    {
        return getAppConfig().getKnownLocaleFlagMap();
    }


    public TokenStorageMethod getTokenStorageMethod( )
    {
        try
        {
            return TokenStorageMethod.valueOf( readSettingAsString( PwmSetting.TOKEN_STORAGEMETHOD ) );
        }
        catch ( final Exception e )
        {
            final String errorMsg = "unknown storage method specified: " + readSettingAsString( PwmSetting.TOKEN_STORAGEMETHOD );
            final ErrorInformation errorInformation = new ErrorInformation( PwmError.ERROR_INVALID_CONFIG, errorMsg );
            LOGGER.warn( () -> errorInformation.toDebugStr() );
            return null;
        }
    }

    public PwmSettingTemplateSet getTemplate( )
    {
        return storedConfiguration.getTemplateSet();
    }

    public boolean hasDbConfigured( )
    {
        return !StringUtil.isEmpty( readSettingAsString( PwmSetting.DATABASE_CLASS ) )
                && !StringUtil.isEmpty( readSettingAsString( PwmSetting.DATABASE_URL ) )
                && !StringUtil.isEmpty( readSettingAsString( PwmSetting.DATABASE_USERNAME ) )
                && readSettingAsPassword( PwmSetting.DATABASE_PASSWORD ) != null;
    }

    public String readAppProperty( final AppProperty property )
    {
        return appConfig.readAppProperty( property );
    }

    private StoredValue readStoredValue( final PwmSetting setting )
    {
        if ( setting.getCategory().hasProfiles() )
        {
            throw new IllegalStateException( "attempt to read setting value for setting '"
                    + setting.getKey() + "' as non-profiled setting " );
        }

        return storedConfiguration.readSetting( setting, null );
    }

    public DomainID getDomainID()
    {
        return domainID;
    }

    private class ConfigurationSuppliers
    {
        private final Supplier<Map<String, LdapProfile>> ldapProfilesSupplier = new LazySupplier<>( () ->
        {
            final Map<String, LdapProfile> sourceMap = getProfileMap( ProfileDefinition.LdapProfile );

            return Collections.unmodifiableMap(
                    sourceMap.entrySet()
                            .stream()
                            .filter( entry -> entry.getValue().isEnabled() )
                            .collect( Collectors.toMap( Map.Entry::getKey, Map.Entry::getValue ) )
            );
        } );

        private final LazySupplier.CheckedSupplier<PwmSecurityKey, PwmUnrecoverableException> pwmSecurityKey
                = LazySupplier.checked( () ->
        {
            final PasswordData configValue = readSettingAsPassword( PwmSetting.PWM_SECURITY_KEY );

            if ( configValue == null || configValue.getStringValue().isEmpty() )
            {
                final String errorMsg = "Security Key value is not configured, will generate temp value for use by runtime instance";
                final ErrorInformation errorInfo = new ErrorInformation( PwmError.ERROR_INVALID_SECURITY_KEY, errorMsg );
                LOGGER.warn( errorInfo::toDebugStr );
                if ( tempInstanceKey == null )
                {
                    tempInstanceKey = new PwmSecurityKey( PwmRandom.getInstance().alphaNumericString( 1024 ) );
                }
                return tempInstanceKey;
            }
            else
            {
                final int minSecurityKeyLength = Integer.parseInt( readAppProperty( AppProperty.SECURITY_CONFIG_MIN_SECURITY_KEY_LENGTH ) );
                if ( configValue.getStringValue().length() < minSecurityKeyLength )
                {
                    final String errorMsg = "Security Key must be greater than 32 characters in length";
                    final ErrorInformation errorInfo = new ErrorInformation( PwmError.ERROR_INVALID_SECURITY_KEY, errorMsg );
                    throw new PwmUnrecoverableException( errorInfo );
                }

                try
                {
                    return new PwmSecurityKey( configValue.getStringValue() );
                }
                catch ( final Exception e )
                {
                    final String errorMsg = "unexpected error generating Security Key crypto: " + e.getMessage();
                    final ErrorInformation errorInfo = new ErrorInformation( PwmError.ERROR_INVALID_SECURITY_KEY, errorMsg );
                    LOGGER.error( errorInfo::toDebugStr, e );
                    throw new PwmUnrecoverableException( errorInfo );
                }
            }
        } );
    }

    private static class DataCache
    {
        private final Map<String, Map<Locale, PwmPasswordPolicy>> cachedPasswordPolicy = new LinkedHashMap<>();
        private final Map<String, Map<Locale, String>> customText = new LinkedHashMap<>();
        private final Map<ProfileDefinition, Map> profileCache = new LinkedHashMap<>();
    }

    public Map<AppProperty, String> readAllNonDefaultAppProperties( )
    {
        final Map<AppProperty, String> nonDefaultProperties = new EnumMap<>( AppProperty.class );
        for ( final AppProperty loopProperty : AppProperty.values() )
        {
            final String configuredValue = readAppProperty( loopProperty );
            final String defaultValue = loopProperty.getDefaultValue();
            if ( !Objects.equals(  configuredValue, defaultValue ) )
            {
                nonDefaultProperties.put( loopProperty, configuredValue );
            }
        }
        return nonDefaultProperties;
    }

    /* generic profile stuff */
    public Map<String, NewUserProfile> getNewUserProfiles( )
    {
        return getProfileMap( ProfileDefinition.NewUser );
    }

    public Map<String, ActivateUserProfile> getUserActivationProfiles( )
    {
        return getProfileMap( ProfileDefinition.ActivateUser );
    }

    public Map<String, HelpdeskProfile> getHelpdeskProfiles( )
    {
        return getProfileMap( ProfileDefinition.Helpdesk );
    }

    public Map<String, EmailServerProfile> getEmailServerProfiles( )
    {
        return getProfileMap( ProfileDefinition.EmailServers );
    }

    public Map<String, PeopleSearchProfile> getPeopleSearchProfiles( )
    {
        return getProfileMap( ProfileDefinition.PeopleSearch );
    }

    public Map<String, SetupOtpProfile> getSetupOTPProfiles( )
    {
        return getProfileMap( ProfileDefinition.SetupOTPProfile );
    }

    public Map<String, UpdateProfileProfile> getUpdateAttributesProfile( )
    {
        return getProfileMap( ProfileDefinition.UpdateAttributes );
    }

    public Map<String, ChangePasswordProfile> getChangePasswordProfile( )
    {
        return getProfileMap( ProfileDefinition.ChangePassword );
    }

    public Map<String, ForgottenPasswordProfile> getForgottenPasswordProfiles( )
    {
        return getProfileMap( ProfileDefinition.ForgottenPassword );
    }

    private <T extends Profile> Map<String, T> getProfileMap( final ProfileDefinition profileDefinition )
    {
        if ( !dataCache.profileCache.containsKey( profileDefinition ) )
        {
            final Map<String, T> returnMap = new LinkedHashMap<>();
            final Map<String, Profile> profileMap = profileMap( profileDefinition );
            for ( final Map.Entry<String, Profile> entry : profileMap.entrySet() )
            {
                returnMap.put( entry.getKey(), ( T ) entry.getValue() );
            }
            dataCache.profileCache.put( profileDefinition, Collections.unmodifiableMap( returnMap ) );
        }
        return dataCache.profileCache.get( profileDefinition );
    }

    public Map<String, Profile> profileMap( final ProfileDefinition profileDefinition )
    {
        final Map<String, Profile> returnMap = new LinkedHashMap<>();
        for ( final String profileID : ProfileUtility.profileIDsForCategory( this, profileDefinition.getCategory() ) )
        {
            if ( profileDefinition.getProfileFactoryClass().isPresent() )
            {
                final Profile newProfile = newProfileForID( profileDefinition, profileID );
                returnMap.put( profileID, newProfile );
            }
        }
        return Collections.unmodifiableMap( returnMap );
    }

    private Profile newProfileForID( final ProfileDefinition profileDefinition, final String profileID )
    {
        Objects.requireNonNull( profileDefinition );
        Objects.requireNonNull( profileID );

        final Optional<Class<? extends Profile.ProfileFactory>> optionalProfileFactoryClass = profileDefinition.getProfileFactoryClass();

        if ( optionalProfileFactoryClass.isPresent() )
        {
            final Profile.ProfileFactory profileFactory;
            try
            {
                profileFactory = optionalProfileFactoryClass.get().getDeclaredConstructor().newInstance();
                return profileFactory.makeFromStoredConfiguration( storedConfiguration, profileID );
            }
            catch ( final InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e )
            {
                throw new IllegalStateException( "unable to create profile instance for " + profileDefinition );
            }
        }

        throw new IllegalStateException( "unable to create profile instance for " + profileDefinition + " ( profile factory class not defined )" );
    }

    public StoredConfiguration getStoredConfiguration( )
    {
        return this.storedConfiguration;
    }

    public boolean isDevDebugMode( )
    {
        return appConfig.isDevDebugMode();
    }

    public String configurationHash( final SecureService secureService )
            throws PwmUnrecoverableException
    {
        return appConfig.configurationHash( secureService );
    }

    public Set<PwmSetting> nonDefaultSettings( )
    {
        final Set<PwmSetting> returnSet = new LinkedHashSet<>();
        for ( final StoredConfigItemKey key : this.storedConfiguration.modifiedItems() )
        {
            if ( key.getRecordType() == StoredConfigItemKey.RecordType.SETTING )
            {
                returnSet.add( key.toPwmSetting() );
            }
        }
        return returnSet;
    }

    public CertificateMatchingMode readCertificateMatchingMode()
    {
        final CertificateMatchingMode mode = readSettingAsEnum( PwmSetting.CERTIFICATE_VALIDATION_MODE, CertificateMatchingMode.class );
        return mode == null
                ? CertificateMatchingMode.CA_ONLY
                : mode;
    }

    public Optional<PeopleSearchProfile> getPublicPeopleSearchProfile()
    {
        if ( readSettingAsBoolean( PwmSetting.PEOPLE_SEARCH_ENABLE_PUBLIC ) )
        {
            final String profileID = readSettingAsString( PwmSetting.PEOPLE_SEARCH_PUBLIC_PROFILE );
            final Map<String, PeopleSearchProfile> profiles = this.getProfileMap( ProfileDefinition.PeopleSearchPublic );
            return Optional.ofNullable( profiles.get( profileID ) );
        }
        return Optional.empty();
    }
}