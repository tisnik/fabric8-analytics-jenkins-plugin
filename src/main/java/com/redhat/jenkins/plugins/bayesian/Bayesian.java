package com.redhat.jenkins.plugins.bayesian;

import java.io.BufferedReader;

/*
 * Copyright 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 *limitations under the License.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.List;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HTTP;


import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import hudson.FilePath;
import com.redhat.jenkins.plugins.bayesian.BayesianResponse;

/* package */ class Bayesian {

    private static final String DEFAULT_BAYESIAN_URL = "https://recommender.api.openshift.io/";
    private static final String DEFAULT_OSIO_USERS_URL = "https://api.openshift.io/api/users";
    private static final String DEFAULT_OSIO_USERS_FILTER = "username";
    private String url;
    private String gitUrl;

    public Bayesian() throws URISyntaxException {
        this(DEFAULT_BAYESIAN_URL, "");
    }

    public Bayesian(String url, String gitUrl) throws URISyntaxException {
        URI uri = new URI(url);
        String host = uri.getHost();
        if (host.indexOf('.') == -1) {
            // looks like it's a short domain name
            // TODO: there can be dots in short domain names as well
            List<String> cnames = DnsFiddler.getActualCNAME(host);
            if (!cnames.isEmpty()) {
                String hostname = cnames.get(0);
                if (hostname.endsWith(".")) {
                    hostname = hostname.substring(0, hostname.length() - 1);
                }
                uri = new URIBuilder(uri).setHost(hostname).build();
            }
            cnames = null;
        }
        this.url = uri.toString();
        this.gitUrl = gitUrl;
    }

    public BayesianStepResponse submitStackForAnalysis(Collection<FilePath> manifests) throws BayesianException {
        String stackAnalysesUrl = getApiUrl() + "/stack-analyses";
        HttpPost httpPost = new HttpPost(stackAnalysesUrl);

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        for (FilePath manifest : manifests) {
            byte[] content = null;
            try (InputStream in = manifest.read()) {
                content = ByteStreams.toByteArray(in);
                builder.addBinaryBody("manifest[]", content, ContentType.DEFAULT_BINARY, manifest.getName());
                String filePath = manifest.getRemote();
                if (filePath.endsWith("pom.xml")){
                    String[] filePathStrs = filePath.split("stackinfo/poms/");
                    filePath = filePathStrs[1];
                }
                builder.addTextBody("filePath[]", filePath, ContentType.TEXT_PLAIN);
            } catch (IOException | InterruptedException e) {
                throw new BayesianException(e);
            } finally {
                content = null;
            }
        }
        HttpEntity multipart = builder.build();
        builder = null;
        httpPost.setEntity(multipart);
        httpPost.setHeader("Authorization", "Bearer " + getAuthToken());
        httpPost.setHeader("UserEmail", getEmail());
        httpPost.setHeader("ScanRepoUrl", getGitUrl());

        BayesianResponse responseObj = null;
        Gson gson;
        try (CloseableHttpClient client = HttpClients.createDefault();
                CloseableHttpResponse response = client.execute(httpPost)) {
            HttpEntity entity = response.getEntity();
            // Yeah, the endpoint actually returns 200 from some reason;
            // I wonder what happened to the good old-fashioned 202 :)
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new BayesianException("Bayesian error: " + response.getStatusLine().getStatusCode());
            }

            Charset charset = ContentType.get(entity).getCharset();
            try (InputStream is = entity.getContent();
                    Reader reader = new InputStreamReader(is, charset != null ? charset : HTTP.DEF_CONTENT_CHARSET)) {
                gson = new GsonBuilder().create();
                responseObj = gson.fromJson(reader, BayesianResponse.class);
                String analysisUrl = stackAnalysesUrl + "/" + responseObj.getId();
                return new BayesianStepResponse(responseObj.getId(), "", analysisUrl, true);
            }
        } catch (IOException e) {
            throw new BayesianException("Bayesian error", e);
        } finally {
            // just to be sure...
            responseObj = null;
            httpPost = null;
            multipart = null;
            gson = null;
        }
    }

    public String getApiUrl() {
        URIBuilder url;
        URI apiUrl = null;
        try {
            url = new URIBuilder(getUrl());
            apiUrl = url.setPath(url.getPath() + "/api/v1").build().normalize();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Bayesian URL is invalid.");
        }
        return apiUrl.toString();
    }

    public String getEmail() throws BayesianException{

        
        String url = getOSIOUserUrl();
        
        if(url.equals("No-Filter-Found")) {
            return "No-Email-Found";
        }
        
        Gson gson;
        User responseObj;

        HttpGet httpGet = new HttpGet(url);
        try (CloseableHttpClient client = HttpClients.createDefault();
                CloseableHttpResponse response = client.execute(httpGet)) {

                HttpEntity entity = response.getEntity();
                Charset charset = ContentType.get(entity).getCharset();
                try (InputStream is = entity.getContent();
                        Reader reader = new InputStreamReader(is, charset != null ? charset : HTTP.DEF_CONTENT_CHARSET)) {

                gson = new GsonBuilder().create();

                responseObj = gson.fromJson(reader, User.class);

                if(responseObj.getData() == null ||
                    responseObj.getData().isEmpty() ||
                    responseObj.getData().get(0) == null ||
                    responseObj.getData().get(0).getAttributes() == null ||
                    responseObj.getData().get(0).getAttributes().getEmail() == null
                    ) {

                        return "No-Email-Found";
                }

                return responseObj.getData().get(0).getAttributes().getEmail();
            }

        } catch (IOException e) {
            throw new BayesianException("Bayesian error", e);
        }
    }

    public String getOSIOUserUrl() {
        String filterData = getFilteringData();        
        if(filterData.equals("Data-Not-Found")) {
            return "No-Filter-Found";
        }
        
        return getOSIOUrl() + "?filter[" + getFilter() + "]=" + filterData;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
    
    public String getGitUrl() {
        return gitUrl;
    }

    public void setGitUrl(String gitUrl) {
        this.gitUrl = gitUrl;
    }

    public static String getDefaultUrl() {
        return DEFAULT_BAYESIAN_URL;
    }
    
    public static String getOSIOUrl() {
        String url = System.getenv("OSIO_USERS_URL");
        return (url != null) ? url : DEFAULT_OSIO_USERS_URL;
    }

    public static String getFilter() {
        String filter = System.getenv("OSIO_USERS_FILTER");
        return (filter != null) ? filter : DEFAULT_OSIO_USERS_FILTER;
    }

    private String getAuthToken() {
        String token = System.getenv("RECOMMENDER_API_TOKEN");
        return (token != null) ? token : "token-not-available-in-pipelines";
    }

    private String getFilteringData() {
        String nameSpace = System.getenv("PROJECT_NAMESPACE");
        return (nameSpace != null) ? nameSpace : "Data-Not-Found";
    }
}
