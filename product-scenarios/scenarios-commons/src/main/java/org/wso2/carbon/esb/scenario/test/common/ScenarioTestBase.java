/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.esb.scenario.test.common;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.awaitility.Awaitility;
import org.wso2.carbon.application.mgt.stub.ApplicationAdminExceptionException;
import org.wso2.carbon.integration.common.admin.client.ApplicationAdminClient;
import org.wso2.carbon.integration.common.admin.client.CarbonAppUploaderClient;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * This is the base class of scenario test classes.
 */
public class ScenarioTestBase {

    private static final String INPUTS_LOCATION = System.getenv("DATA_BUCKET_LOCATION");
    private static final String INFRASTRUCTURE_PROPERTIES = "infrastructure.properties";
    private static final String DEPLOYMENT_PROPERTIES = "deployment.properties";
    private static final String JOB_PROPERTIES = "testplan-props.properties";

    public static final Log log = LogFactory.getLog(ScenarioTestBase.class);

    protected static final String resourceLocation = System.getProperty(ScenarioConstants.TEST_RESOURCES_DIR);

    protected Properties infraProperties;
    protected String backendURL;
    protected String serviceURL;
    protected String securedServiceURL;
    protected String sessionCookie;
    protected boolean standaloneMode;

    protected CarbonAppUploaderClient carbonAppUploaderClient = null;
    protected ApplicationAdminClient applicationAdminClient = null;

    /**
     * Initialize testcase
     *
     * @throws Exception
     */
    public void init() throws Exception {
        log.info("Started Executing Scenario TestBase ");

        infraProperties = getDeploymentProperties();

        backendURL = infraProperties.getProperty(ScenarioConstants.CARBON_SERVER_URL) +
                (infraProperties.getProperty(ScenarioConstants.CARBON_SERVER_URL).endsWith("/") ? "" : "/");
        serviceURL = infraProperties.getProperty(ScenarioConstants.ESB_HTTP_URL) +
                (infraProperties.getProperty(ScenarioConstants.ESB_HTTP_URL).endsWith("/") ? "" : "/");
        securedServiceURL = infraProperties.getProperty(ScenarioConstants.ESB_HTTPS_URL) +
                (infraProperties.getProperty(ScenarioConstants.ESB_HTTPS_URL).endsWith("/") ? "" : "/");

        //standaloneMode = Boolean.valueOf(infraProperties.getProperty(STANDALONE_DEPLOYMENT));
        // TODO : remove this once test environment is stable
        standaloneMode = true;

        setKeyStoreProperties();

        // login
        AuthenticatorClient authenticatorClient = new AuthenticatorClient(backendURL);
        sessionCookie = authenticatorClient.login("admin", "admin", getServerHost());

        log.info("Service URL: " + serviceURL + " | Secured Service URL: " + securedServiceURL);
        log.info("The Backend service URL : " + backendURL + ". session cookie: " + sessionCookie);

    }

    /**
     * Perform cleanup
     * @throws Exception
     */
    public void cleanup() throws Exception {
    }

    /**
     * This is a utility method to load the deployment details.
     * The deployment details are available as key-value pairs in {@link #INFRASTRUCTURE_PROPERTIES},
     * {@link #DEPLOYMENT_PROPERTIES}, and {@link #JOB_PROPERTIES} under the
     * {@link #INPUTS_LOCATION}.
     *
     * This method loads these files into one single properties, and return it.
     *
     * @return properties the deployment properties
     */
    public static Properties getDeploymentProperties() {
        Path infraPropsFile = Paths.get(INPUTS_LOCATION + File.separator + INFRASTRUCTURE_PROPERTIES);
        Path deployPropsFile = Paths.get(INPUTS_LOCATION + File.separator + DEPLOYMENT_PROPERTIES);
        Path jobPropsFile = Paths.get(INPUTS_LOCATION + File.separator + JOB_PROPERTIES);

        Properties props = new Properties();
        loadProperties(infraPropsFile, props);
        loadProperties(deployPropsFile, props);
        loadProperties(jobPropsFile, props);
        return props;
    }


    public String getApiInvocationURLHttp(String resourcePath) {
        return serviceURL + (resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath);
    }

    public String getApiInvocationURLHttps(String resourcePath) {
        return securedServiceURL + (resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath);
    }

    protected String getProxyServiceURLHttp(String proxyServiceName) {
        return serviceURL + "services" + (proxyServiceName.startsWith("/") ? "" : "/") + proxyServiceName;
    }

    protected String getProxyServiceURLHttps(String proxyServiceName) {
        return securedServiceURL + "services" + (proxyServiceName.startsWith("/") ? "" : "/") + proxyServiceName;
    }

    /**
     * Function to upload carbon application
     *
     * @param carFileName
     * @return
     * @throws RemoteException
     */
    public void deployCarbonApplication(String carFileName) throws RemoteException, InterruptedException {

        if (standaloneMode) {
            // If standalone mode, deploy the CAPP to the server
            String cappFilePath = resourceLocation + File.separator + "artifacts" +
                    File.separator + carFileName + ".car";

            if (carbonAppUploaderClient == null) {
                carbonAppUploaderClient = new CarbonAppUploaderClient(backendURL, sessionCookie);
            }
            DataHandler dh = new DataHandler(new FileDataSource(new File(cappFilePath)));
            // Upload carbon application
            carbonAppUploaderClient.uploadCarbonAppArtifact(carFileName + ".car", dh);

            //TODO - This thread sleep is added temporarily to wait until the ESB Instances sync in the cluster in clustered test environment
            if (!Boolean.valueOf(infraProperties.getProperty(ScenarioConstants.STANDALONE_DEPLOYMENT))) {
                log.info("Waiting for artifacts synchronized across cluster nodes");
                Thread.sleep(120000);
            }

            if (applicationAdminClient == null) {
                applicationAdminClient = new ApplicationAdminClient(backendURL, sessionCookie);
            }

            // Wait for Capp to sync
            log.info("Waiting for Carbon Application deployment ..");
            Awaitility.await()
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .atMost(ScenarioConstants.ARTIFACT_DEPLOYMENT_WAIT_TIME_MS, TimeUnit.MILLISECONDS)
                    .until(isCAppDeployed(applicationAdminClient, carFileName));
        }
    }

    /**
     * Function to undeploy carbon application
     *
     * @param applicationName
     * @throws ApplicationAdminExceptionException
     * @throws RemoteException
     */
    public void undeployCarbonApplication(String applicationName)
            throws ApplicationAdminExceptionException, RemoteException {
        if (standaloneMode) {
            applicationAdminClient.deleteApplication(applicationName);

            // Wait for Capp to undeploy
            Awaitility.await()
                      .pollInterval(500, TimeUnit.MILLISECONDS)
                      .atMost(ScenarioConstants.ARTIFACT_DEPLOYMENT_WAIT_TIME_MS, TimeUnit.MILLISECONDS)
                      .until(isCAppUnDeployed(applicationAdminClient, applicationName));
        }

    }

    private static void loadProperties(Path propsFile, Properties props) {
        String msg = "Deployment property file not found: ";
        if (!Files.exists(propsFile)) {
            log.warn(msg + propsFile);
            return;
        }

        try (InputStream propsIS = Files.newInputStream(propsFile)) {
            props.load(propsIS);
        } catch (IOException ex) {
            log.warn(ex.getMessage(), ex);
        }
    }

    protected void setKeyStoreProperties() {
        System.setProperty("javax.net.ssl.trustStore", resourceLocation + "/keystores/wso2carbon.jks");
        System.setProperty("javax.net.ssl.trustStorePassword", "wso2carbon");
        System.setProperty("javax.net.ssl.trustStoreType", "JKS");
    }



    private String getServerHost() {
        String url = infraProperties.getProperty(ScenarioConstants.MGT_CONSOLE_URL);
        if (url != null && url.contains("/")) {
            url = url.split("/")[2].split(":")[0];
        } else
        if (url == null) {
            url = "localhost";
        }
        log.info("Backend URL is set as : " + url);
        return url;
    }


    private Callable <Boolean> isCAppDeployed(final ApplicationAdminClient applicationAdminClient, final String cAppName) {
        return new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                log.info("Check CApp deployment : " + cAppName);
                String[] applicationList = applicationAdminClient.listAllApplications();
                if (applicationList != null) {
                    for (String app : applicationList) {
                        if (app.equals(cAppName)) {
                            log.info("Carbon Application : " + cAppName + " Successfully deployed");
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }

    private Callable <Boolean> isCAppUnDeployed(final ApplicationAdminClient applicationAdminClient, final String cAppName) {
        return new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                log.info("Check CApp un-deployment : " + cAppName);
                boolean undeployed = true;
                String[] applicationList = applicationAdminClient.listAllApplications();
                if (applicationList != null) {
                    for (String app : applicationList) {
                        if (app.equals(cAppName)) {
                            undeployed = false;
                        }
                    }
                }
                if (undeployed) log.info("Carbon Application : " + cAppName + " Successfully un-deployed");
                return undeployed;
            }
        };
    }

    /**
     * Get the list of request,response and header of the each testcase
     *
     * @param testCase - the name of the testCase. Please follow the common conventions to store request and response files
     * @return - List of Arrays consisting request, response and header.
     * @throws IOException - if and error occurs file extracting the file content
     */
    protected List<Object[]> getRequestResponseHeaderList(String testCase) throws IOException {
        String relativeRequestFolderLocation = File.separator + ScenarioConstants.SOURCE_FILES +
                                       File.separator + testCase +
                                       File.separator + ScenarioConstants.REQUEST;

        String relativeResponseFolderLocation = File.separator + ScenarioConstants.SOURCE_FILES +
                                        File.separator + testCase +
                                        File.separator + ScenarioConstants.RESPONSE;

        List<String> requestFiles = getListOfFiles(relativeRequestFolderLocation);
        List<String> responseFiles = getListOfFiles(relativeResponseFolderLocation);

        java.util.Collections.sort(requestFiles, Collator.getInstance());
        java.util.Collections.sort(responseFiles, Collator.getInstance());

        ArrayList<String> requestArray = new ArrayList();
        ArrayList<String> responseArray = new ArrayList();
        ArrayList<String> headerArray = new ArrayList();


        for (String file : requestFiles) {
            String fileContent = getFileContent(relativeRequestFolderLocation, file);
            requestArray.add(fileContent);
            String header = FilenameUtils.removeExtension(file);
            headerArray.add(header);
        }

        for (String file : responseFiles) {
            String fileContent = getFileContent(relativeResponseFolderLocation, file);
            responseArray.add(fileContent);
        }

        List<Object[]> requestResponseList = new ArrayList<>();

        for (int i = 0; i < requestArray.size(); i++) {
            String[] tmp = { requestArray.get(i) , responseArray.get(i) , headerArray.get(i)};
            requestResponseList.add(tmp);
        }
        return requestResponseList;
    }

    private List<String> getListOfFiles(String folderLocation) {
        File filePath = new File(getClass().getResource(folderLocation).getPath());
        File[] listOfFiles = filePath.listFiles();
        List<String> fileNames = new ArrayList<>();

        for (File file:listOfFiles) {
            if (file.isFile()) {
                fileNames.add(file.getName());
            }
        }
        return fileNames;
    }

    private String getFileContent (String folderLocation, String fileName) throws IOException {
        File fileLocation = new File( getClass().getResource(folderLocation + File.separator + fileName).getPath());

        final BufferedReader br = new BufferedReader(new FileReader(new File(String.valueOf(fileLocation))));
        StringBuilder sb = new StringBuilder();

        try {
            String currentLine;
            while ((currentLine = br.readLine()) != null) {
                sb.append(currentLine);
            }
        } finally {
            br.close();
        }

        return sb.toString();
    }
}
