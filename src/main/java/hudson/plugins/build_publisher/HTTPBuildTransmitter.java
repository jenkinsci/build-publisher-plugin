package hudson.plugins.build_publisher;

import hudson.Functions;
import hudson.XmlFile;
import hudson.maven.MavenModule;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Build;
import hudson.model.Result;
import hudson.util.XStream2;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URI;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.methods.FileRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.dom4j.tree.DefaultElement;

import com.thoughtworks.xstream.XStream;

/**
 * Sends build result via HTTP protocol.
 *
 */
public class HTTPBuildTransmitter extends BuildTransmitter {

    private PostMethod method;
    private boolean aborted = false;

    @Override
    protected void proceedTransmission(AbstractBuild build,
            AbstractProject project, HudsonInstance hudsonInstance)
            throws IOException {

        aborted = false;

        String jobUrl = "job/";
        if (project instanceof MavenModule) {
            jobUrl += ((MavenModule) project).getParent().getName()
                    + "/"
                    + ((MavenModule) project).getModuleName()
                            .toFileSystemName();
        } else {
            jobUrl += project.getName();
        }

        method = new PostMethod(hudsonInstance.getUrl()
                + encodeURI(jobUrl) + "/postBuild/acceptBuild");

        File tempFile = null;
        try {

            tempFile = File.createTempFile("hudson_bp", ".tar");
            OutputStream out = new FileOutputStream(tempFile);
            writeToTar(out, build);

            method.setRequestEntity(new FileRequestEntity(tempFile,
                    "application/x-tar"));

            int responseCode = executeMethod(method, hudsonInstance);
            if (responseCode >= 400) {
                // transmission probably failed. Let's notify sender
                throw new HttpException(method.getURI()
                        + ": Server responded with status " + responseCode);
            }
        } catch (IOException e) {
            // May be caused by premature call of HttpMethod.abort()
            if (!aborted) {
                throw (e);
            }
        } catch (RuntimeException e1) {
            if (!aborted) {
                throw (e1);
            }
        } finally {
            if (!tempFile.delete()) {
                throw new IOException("Failed to delete temporary file "
                        + tempFile.getAbsolutePath()
                        + ". Please delete the file manually.");
            }
        }

    }

    @Override
    public void abortTransmission() {
        aborted = true;
        if (method != null) {
            method.abort();
        }
    }

    /* Follows redirects, authenticates if necessary. */
    static int executeMethod(HttpMethodBase method,
            HudsonInstance hudsonInstance) throws IOException {
        int statusCode = followRedirects(method, hudsonInstance);

        if ((statusCode >= 401) && (statusCode <= 403)) {
            // Authentication failed, let's try FORM method
            GetMethod loginMethod = new GetMethod(hudsonInstance.getUrl()
                    + "loginEntry");
            statusCode = followRedirects(loginMethod, hudsonInstance);

            PostMethod credentialsMethod = new PostMethod(hudsonInstance
                    .getUrl()
                    + "j_security_check");
            credentialsMethod.addParameter("j_username", hudsonInstance
                    .getLogin());
            credentialsMethod.addParameter("j_password", hudsonInstance
                    .getPassword());
            credentialsMethod.addParameter("action", "login");
            statusCode = followRedirects(credentialsMethod, hudsonInstance);
            // another attempt
            statusCode = followRedirects(method, hudsonInstance);
        }

        return statusCode;
    }

    private static int followRedirects(HttpMethodBase method,
            HudsonInstance hudsonInstance) throws IOException {
        int statusCode;
        HttpClient client = hudsonInstance.getHttpClient();
        try {
            statusCode = client.executeMethod(method);
        } finally {
            method.releaseConnection();
        }

        if ((statusCode >= 300) && (statusCode < 400)) {
            Header locationHeader = method.getResponseHeader("location");
            if (locationHeader != null) {
                String redirectLocation = locationHeader.getValue();
                method
                        .setURI(new org.apache.commons.httpclient.URI(/*method.getURI(),*/ redirectLocation,
                                true));
                statusCode = followRedirects(method, hudsonInstance);
            }
        }

        return statusCode;
    }

    /**
     * Writes to a tar stream and stores obtained files to the base dir.
     *
     * @return number of files/directories that are written.
     */
    // most of this is taken from somewhere of Hudson code. Perhaps it would be
    // good idea to put it in one place.
    private Integer writeToTar(OutputStream out, AbstractBuild build)
            throws IOException {
        File buildDir = build.getRootDir();
        File baseDir = buildDir.getParentFile();
        String buildXmlFile = buildDir.getName() + "/build.xml";
        FileSet fileSet = new FileSet();
        fileSet.setDir(baseDir);
        fileSet.setIncludes(buildDir.getName() + "/**");
        fileSet.setExcludes(buildXmlFile);

        byte[] buffer = new byte[8192];

        TarOutputStream tar = new TarOutputStream(new BufferedOutputStream(out));
        tar.setLongFileMode(TarOutputStream.LONGFILE_GNU);

        DirectoryScanner dirScanner = fileSet
                .getDirectoryScanner(new org.apache.tools.ant.Project());
        String[] files = dirScanner.getIncludedFiles();
        for (String fileName : files) {

            if (aborted) {
                break;
            }

            if (Functions.isWindows()) {
                fileName = fileName.replace('\\', '/');
            }

            File file = new File(baseDir, fileName);

            if (!file.isDirectory()) {
                writeStreamToTar(tar, new FileInputStream(file), fileName, file
                        .length(), buffer);
            }
        }

        File buildFile = new File(build.getRootDir(), "build.xml");
        String buildXml = updateBuildXml(buildFile, build);
        byte[] bytes = buildXml.getBytes();
        writeStreamToTar(tar, new ByteArrayInputStream(bytes), buildXmlFile,
                bytes.length, buffer);

        tar.close();

        return files.length;
    }

    private String updateBuildXml(File buildFile, AbstractBuild build)
            throws IOException {
        XmlFile file = new XmlFile(buildFile);
        SAXReader reader = new SAXReader();

        try {
            Document document = reader.read(buildFile);

            Node property = document
                    .selectSingleNode("//actions/hudson.plugins.build__publisher.StatusAction");
            if (property != null) {
                property.detach();
            }

            if (build instanceof MavenModuleSetBuild) {
                Element root = document.getRootElement();
                Element result = root.element("result");
                if (result == null) {
                    result = new DefaultElement("result");
                    root.add(result);
                }
                // hudson sets FAILURE as default result, which isn't overriden
                // by successful modules on the public instance
                result.setText(Result.SUCCESS.toString());
            }

            return document.asXML();
        } catch (DocumentException e) {
            e.printStackTrace();
            return file.asString();
        }
    }

    private void writeStreamToTar(TarOutputStream tar, InputStream in,
            String fileName, long length, byte[] buf) throws IOException {
        TarEntry te = new TarEntry(fileName);
        te.setSize(length);

        tar.putNextEntry(te);

        int len;
        while ((len = in.read(buf)) >= 0) {

            if (aborted) {
                break;
            }

            tar.write(buf, 0, len);
        }
        tar.closeEntry();

        in.close();
    }

    public static String encodeURI(String uri) {
        try {
            return new URI(null,uri,null).toASCIIString();
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return uri;
        }
    }


}
