/* 
 * Copyright (C) 2018 Dmitry Avtonomov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package umich.msfragger.params.fragger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPart;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import umich.msfragger.gui.api.VersionFetcher;
import umich.msfragger.util.PropertiesUtils;
import umich.msfragger.util.StringUtils;

/**
 *
 * @author Dmitry Avtonomov
 */
public class MsfraggerVersionFetcherServer implements VersionFetcher {
    
    private final Pattern re = Pattern.compile("([\\d.]+)");
    String latestVerResponse = null;
    String latestVerParsed = null;
    String downloadUrl = null;
    /** Properties are persisted here after fetchVersion() call, in case we need to auto-update. */
    Properties props = null;
    
    @Override
    public String fetchVersion() throws MalformedURLException, IOException {
        
        
        props = loadProps();
        
        String verSvcResponse = fetchVersionResponse();
        
        Matcher m = re.matcher(verSvcResponse);
        if (m.find()) {
            downloadUrl = props.getProperty(MsfraggerProps.PROP_UPDATESERVER_WEBSITE_URL, MsfraggerProps.DOWNLOAD_URL);
            latestVerResponse = verSvcResponse;
            latestVerParsed = m.group(1);
            return m.group(1);
        }
        
        throw new IllegalStateException("Version string retrieved from the remote service was not recoginsed: '" + verSvcResponse + "'");
    }
    
    private Properties loadProps() {
        Properties p = PropertiesUtils.loadPropertiesRemoteOrLocal(Collections.singletonList(MsfraggerProps.PROPERTIES_URI), 
                MsfraggerProps.class, MsfraggerProps.PROPERTIES_FILE_NAME);
        if (p == null)
            throw new IllegalStateException(String.format("Could not laod %s "
                    + "neither from GitHub nor from local jar", MsfraggerProps.PROPERTIES_FILE_NAME));
        return p;
    }

    @Override
    public String getDownloadUrl() {
        return downloadUrl;
    }

    @Override
    public String getToolName() {
        return MsfraggerProps.PROGRAM_NAME;
    }

    @Override
    public boolean canAutoUpdate() {
        return true;
    }

    private String fetchVersionResponse() throws MalformedURLException, IOException {
        String serviceUrl = props.getProperty(MsfraggerProps.PROP_UPDATESERVER_VERSION_URL);
        if (StringUtils.isNullOrWhitespace(serviceUrl))
            throw new IllegalStateException("Could not get versionServiceUrl");
        String response = org.apache.commons.io.IOUtils.toString(new URL(serviceUrl), Charset.forName("UTF-8"));
        if (StringUtils.isNullOrWhitespace(response))
            throw new IllegalStateException("Update server returned empty string for the latest available version.");
        return response.trim();
    }
    
    @Override
    public Path autoUpdate(Path p) throws MalformedURLException, IOException {
        if (p == null || !Files.exists(p) || Files.isDirectory(p))
            throw new IllegalArgumentException("The path to file to be updated must be non-null, must exist and not point to a directory.");
        
        String lastVersionStr = fetchVersion();
        
        String updateSvcUrl = props.getProperty(MsfraggerProps.PROP_UPDATESERVER_UPDATE_URL);
        if (updateSvcUrl == null)
            throw new IllegalStateException("Obtained properties file didn't contain a URL for the updater service.");
        
        if (StringUtils.isNullOrWhitespace(latestVerResponse))
            latestVerResponse = fetchVersionResponse();
        
        
        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost post = new HttpPost(updateSvcUrl);

        final FormBodyPart formPartDownload = FormBodyPartBuilder.create()
                .setName("download")
                .setBody(new StringBody(latestVerResponse + "$jar", ContentType.TEXT_PLAIN))
                .build();
        final FormBodyPart formPartTradeinFile = FormBodyPartBuilder.create()
                .setName("jarkey")
                .setBody(new FileBody(p.toFile()))
                .build();

        HttpEntity req = MultipartEntityBuilder.create()
                .addPart(formPartDownload)
                .addPart(formPartTradeinFile)
                .build();
        post.setEntity(req);

        try (final CloseableHttpResponse response = client.execute(post)) {
            final HttpEntity entity = response.getEntity();
            final long contentLength = entity.getContentLength();
            final Header[] headers = response.getAllHeaders();
            String file = null;
            outerLoop:
            for (Header h : headers) {
                for (HeaderElement he : h.getElements()) {
                    if ("attachment".equalsIgnoreCase(he.getName())) {
                        NameValuePair nvp = he.getParameterByName("filename");
                        if (nvp == null)
                            continue;
                        file = nvp.getValue();
                        break outerLoop;
                    }
                }
            }
            
            boolean gotName = !StringUtils.isNullOrWhitespace(file);
            int dot = file != null ? file.lastIndexOf('.') : -1;
                
            final String nameBase = gotName && dot > 0 ? file.substring(0, dot) : "MSFragger-" + lastVersionStr;
            final String nameExt = gotName && dot > 0 ? file.substring(dot) : ".jar";
            Path pathOut = p.resolveSibling(nameBase + nameExt);
            if (Files.exists(pathOut)) {
                // find another suitable name
                pathOut = Files.createTempFile(p.getParent(), nameBase + "_dup_", nameExt);
            }
            final byte[] upgradedFragger = EntityUtils.toByteArray(entity);
            
            try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(pathOut))) {
                bos.write(upgradedFragger);
            }
            EntityUtils.consumeQuietly(entity);
            
            return pathOut;
        }
    }
    
}
