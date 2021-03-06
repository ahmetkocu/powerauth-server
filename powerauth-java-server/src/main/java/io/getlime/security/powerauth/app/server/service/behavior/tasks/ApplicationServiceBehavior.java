/*
 * PowerAuth Server and related software components
 * Copyright (C) 2017 Lime - HighTech Solutions s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.getlime.security.powerauth.app.server.service.behavior.tasks;

import com.google.common.io.BaseEncoding;
import io.getlime.security.powerauth.*;
import io.getlime.security.powerauth.app.server.database.RepositoryCatalogue;
import io.getlime.security.powerauth.app.server.database.model.entity.ApplicationEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.ApplicationVersionEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.MasterKeyPairEntity;
import io.getlime.security.powerauth.app.server.service.exceptions.GenericServiceException;
import io.getlime.security.powerauth.app.server.service.i18n.LocalizationProvider;
import io.getlime.security.powerauth.app.server.service.model.ServiceError;
import io.getlime.security.powerauth.crypto.lib.generator.KeyGenerator;
import io.getlime.security.powerauth.provider.CryptoProviderUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * Behavior class implementing the application management related processes. The class separates the
 * logic from the main service class.
 *
 * @author Petr Dvorak, petr@lime-company.eu
 */
@Component
public class ApplicationServiceBehavior {

    private RepositoryCatalogue repositoryCatalogue;
    private LocalizationProvider localizationProvider;

    @Autowired
    public ApplicationServiceBehavior(RepositoryCatalogue repositoryCatalogue, LocalizationProvider localizationProvider) {
        this.repositoryCatalogue = repositoryCatalogue;
        this.localizationProvider = localizationProvider;
    }

    /**
     * Get application details.
     *
     * @param applicationId Application ID
     * @return Response with application details
     */
    public GetApplicationDetailResponse getApplicationDetail(Long applicationId) throws GenericServiceException {

        ApplicationEntity application = repositoryCatalogue.getApplicationRepository().findOne(applicationId);
        if (application == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_APPLICATION);
        }

        GetApplicationDetailResponse response = new GetApplicationDetailResponse();
        response.setApplicationId(application.getId());
        response.setApplicationName(application.getName());
        response.setMasterPublicKey(repositoryCatalogue.getMasterKeyPairRepository().findFirstByApplicationIdOrderByTimestampCreatedDesc(application.getId()).getMasterKeyPublicBase64());

        List<ApplicationVersionEntity> versions = repositoryCatalogue.getApplicationVersionRepository().findByApplicationId(application.getId());
        for (ApplicationVersionEntity version : versions) {

            GetApplicationDetailResponse.Versions ver = new GetApplicationDetailResponse.Versions();
            ver.setApplicationVersionId(version.getId());
            ver.setApplicationKey(version.getApplicationKey());
            ver.setApplicationSecret(version.getApplicationSecret());
            ver.setApplicationVersionName(version.getName());
            ver.setSupported(version.getSupported());

            response.getVersions().add(ver);
        }

        return response;
    }

    /**
     * Lookup application based on version app key.
     *
     * @param appKey Application version key (APP_KEY).
     * @return Response with application details
     */
    public LookupApplicationByAppKeyResponse lookupApplicationByAppKey(String appKey) throws GenericServiceException {
        ApplicationVersionEntity applicationVersion = repositoryCatalogue.getApplicationVersionRepository().findByApplicationKey(appKey);
        if (applicationVersion == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_APPLICATION);
        }
        ApplicationEntity application = repositoryCatalogue.getApplicationRepository().findOne(applicationVersion.getApplication().getId());
        if (application == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_APPLICATION);
        }
        LookupApplicationByAppKeyResponse response = new LookupApplicationByAppKeyResponse();
        response.setApplicationId(application.getId());
        return response;
    }

    /**
     * Get application list in the PowerAuth Server instance.
     *
     * @return List of applications.
     */
    public GetApplicationListResponse getApplicationList() {

        Iterable<ApplicationEntity> result = repositoryCatalogue.getApplicationRepository().findAll();

        GetApplicationListResponse response = new GetApplicationListResponse();

        for (Iterator<ApplicationEntity> iterator = result.iterator(); iterator.hasNext(); ) {
            ApplicationEntity application = (ApplicationEntity) iterator.next();
            GetApplicationListResponse.Applications app = new GetApplicationListResponse.Applications();
            app.setId(application.getId());
            app.setApplicationName(application.getName());
            response.getApplications().add(app);
        }

        return response;
    }

    /**
     * Create a new application with given name.
     *
     * @param name                   Application name
     * @param keyConversionUtilities Utility class for the key conversion
     * @return Response with new application information
     */
    public CreateApplicationResponse createApplication(String name, CryptoProviderUtil keyConversionUtilities) {

        ApplicationEntity application = new ApplicationEntity();
        application.setName(name);
        application = repositoryCatalogue.getApplicationRepository().save(application);

        KeyGenerator keyGen = new KeyGenerator();
        KeyPair kp = keyGen.generateKeyPair();
        PrivateKey privateKey = kp.getPrivate();
        PublicKey publicKey = kp.getPublic();

        // Generate the default master key pair
        MasterKeyPairEntity keyPair = new MasterKeyPairEntity();
        keyPair.setApplication(application);
        keyPair.setMasterKeyPrivateBase64(BaseEncoding.base64().encode(keyConversionUtilities.convertPrivateKeyToBytes(privateKey)));
        keyPair.setMasterKeyPublicBase64(BaseEncoding.base64().encode(keyConversionUtilities.convertPublicKeyToBytes(publicKey)));
        keyPair.setTimestampCreated(new Date());
        keyPair.setName(name + " Default Keypair");
        repositoryCatalogue.getMasterKeyPairRepository().save(keyPair);

        // Create the default application version
        byte[] applicationKeyBytes = keyGen.generateRandomBytes(16);
        byte[] applicationSecretBytes = keyGen.generateRandomBytes(16);
        ApplicationVersionEntity version = new ApplicationVersionEntity();
        version.setApplication(application);
        version.setName("default");
        version.setSupported(true);
        version.setApplicationKey(BaseEncoding.base64().encode(applicationKeyBytes));
        version.setApplicationSecret(BaseEncoding.base64().encode(applicationSecretBytes));
        repositoryCatalogue.getApplicationVersionRepository().save(version);

        CreateApplicationResponse response = new CreateApplicationResponse();
        response.setApplicationId(application.getId());
        response.setApplicationName(application.getName());

        return response;
    }

    /**
     * Create a new application version
     *
     * @param applicationId Application ID
     * @param versionName   Application version name
     * @return Response with new version information
     */
    public CreateApplicationVersionResponse createApplicationVersion(Long applicationId, String versionName) throws GenericServiceException {

        ApplicationEntity application = repositoryCatalogue.getApplicationRepository().findOne(applicationId);

        if (application == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_APPLICATION);
        }

        KeyGenerator keyGen = new KeyGenerator();
        byte[] applicationKeyBytes = keyGen.generateRandomBytes(16);
        byte[] applicationSecretBytes = keyGen.generateRandomBytes(16);

        ApplicationVersionEntity version = new ApplicationVersionEntity();
        version.setApplication(application);
        version.setName(versionName);
        version.setSupported(true);
        version.setApplicationKey(BaseEncoding.base64().encode(applicationKeyBytes));
        version.setApplicationSecret(BaseEncoding.base64().encode(applicationSecretBytes));
        version = repositoryCatalogue.getApplicationVersionRepository().save(version);

        CreateApplicationVersionResponse response = new CreateApplicationVersionResponse();
        response.setApplicationVersionId(version.getId());
        response.setApplicationVersionName(version.getName());
        response.setApplicationKey(version.getApplicationKey());
        response.setApplicationSecret(version.getApplicationSecret());
        response.setSupported(version.getSupported());

        return response;
    }

    /**
     * Mark a version with given ID as unsupported
     *
     * @param versionId Version ID
     * @return Response confirming the operation
     */
    public UnsupportApplicationVersionResponse unsupportApplicationVersion(Long versionId) throws GenericServiceException {

        ApplicationVersionEntity version = repositoryCatalogue.getApplicationVersionRepository().findOne(versionId);

        if (version == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_APPLICATION);
        }

        version.setSupported(false);
        version = repositoryCatalogue.getApplicationVersionRepository().save(version);

        UnsupportApplicationVersionResponse response = new UnsupportApplicationVersionResponse();
        response.setApplicationVersionId(version.getId());
        response.setSupported(version.getSupported());

        return response;
    }

    /**
     * Mark a version with given ID as supported
     *
     * @param versionId Version ID
     * @return Response confirming the operation
     */
    public SupportApplicationVersionResponse supportApplicationVersion(Long versionId) throws GenericServiceException {

        ApplicationVersionEntity version = repositoryCatalogue.getApplicationVersionRepository().findOne(versionId);

        if (version == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_APPLICATION);
        }

        version.setSupported(true);
        version = repositoryCatalogue.getApplicationVersionRepository().save(version);

        SupportApplicationVersionResponse response = new SupportApplicationVersionResponse();
        response.setApplicationVersionId(version.getId());
        response.setSupported(version.getSupported());

        return response;
    }

}
