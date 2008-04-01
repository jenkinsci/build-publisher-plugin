package hudson.plugins.build_publisher;

import hudson.Functions;
import hudson.Util;
import hudson.matrix.MatrixConfiguration;
import hudson.maven.MavenModule;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.FileRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Level;
import org.apache.commons.httpclient.HttpException;

/**
 * Sends build result via HTTP protocol.
 *
 */
public class HTTPBuildTransmitter implements BuildTransmitter {

    private PostMethod method;
    private boolean aborted = false;

    @Override
    public void sendBuild(AbstractBuild build, HudsonInstance hudsonInstance)
            throws ServerFailureException {

        aborted = false;

        AbstractProject project = build.getProject();
        
        String jobUrl = "job/";
        if (project instanceof MavenModule) {
            jobUrl += ((MavenModule) project).getParent().getName()
                    + "/"
                    + ((MavenModule) project).getModuleName()
                            .toFileSystemName();
        } else if (project instanceof MatrixConfiguration) {
            jobUrl += ((MatrixConfiguration)project).getParent().getName()
                    + "/"
                    + ((MatrixConfiguration)project).getCombination().toString();
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

            executeMethod(method, hudsonInstance);
            
            //Check if remote side really accepted the build
            Header responseHeader = method.getResponseHeader("X-Build-Recieved");
            if((responseHeader == null) || 
                    !project.getName().equals(responseHeader.getValue().trim())) {
                    throw new HttpException("Remote instance didn't confirm recieving this build");
            }
            
        } catch (IOException e) {
            // May be caused by premature call of HttpMethod.abort()
            if (!aborted) {
                throw new ServerFailureException(method,e);
            }
        } catch (RuntimeException e1) {
            if (!aborted) {
                throw (e1);
            }
        } finally {
            if (!tempFile.delete()) {
                HudsonInstance.LOGGER.log(Level.SEVERE, "Failed to delete temporary file "
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

    /**
     * Executes the given method, with authenticates if necessary,
     * and follow any redirects.
     *
     * @return
     *      Final {@link HttpMethod} that successfully executed,
     *      after possible redirects. The status code of this is always &lt;300 because
     *      this method handles redirection and errors are thrown as exceptions.
     *
     *      <p>
     *      The return value is useful if the caller wants to read the response.
     *
     * @throws ServerFailureException
     *      If we encounter >400 error code from the server.
     * @throws IOException
     *      Other generic communication exception.
     */
    static HttpMethod executeMethod(HttpMethodBase method,
            HudsonInstance hudsonInstance) throws ServerFailureException {
        hudsonInstance.getHttpClient().getState().clear();
        if ((hudsonInstance.requiresAuthentication())) {
            // We need to get authenticated.
            // On some containers and depending on the security configuration,
            // simply sending HTTP BASIC auth would work, but in legacy authentication
            // with some containers in particular, the behavior tends to be
            // different.
            // So while lengthy, let's emulate the user behavior when
            // they clock the login link, which is most stable across different
            // environment
            GetMethod loginMethod = new GetMethod(hudsonInstance.getUrl()
                    + "loginEntry");
            followRedirects(loginMethod, hudsonInstance);

            PostMethod credentialsMethod = new PostMethod(hudsonInstance
                    .getUrl()
                    + "j_security_check");
            credentialsMethod.addParameter("j_username", hudsonInstance
                    .getLogin());
            credentialsMethod.addParameter("j_password", hudsonInstance
                    .getPassword());
            credentialsMethod.addParameter("action", "login");
            followRedirects(credentialsMethod, hudsonInstance);
        }

        return followRedirects(method, hudsonInstance);
    }

    // see executeMethod for contracts
    private static HttpMethod followRedirects(HttpMethodBase method,
            HudsonInstance hudsonInstance) throws ServerFailureException {
        int statusCode;
        HttpClient client = hudsonInstance.getHttpClient();
        try {
            statusCode = client.executeMethod(method);

            if(statusCode<300)
                return method;
            if(statusCode<400) {
                Header locationHeader = method.getResponseHeader("location");
                if (locationHeader != null) {
                    String redirectLocation = locationHeader.getValue();
                    method.setURI(new org.apache.commons.httpclient.URI(/*method.getURI(),*/ redirectLocation,
                                    true));
                    return followRedirects(method, hudsonInstance);
                }
            }

            // failure
            throw new ServerFailureException(method);
        } catch(IOException ioe) {
            throw new ServerFailureException(method, ioe);
        } finally {
            method.releaseConnection();
        }
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
        String buildXml = Util.loadFile(buildFile);
        byte[] bytes = buildXml.getBytes();
        writeStreamToTar(tar, new ByteArrayInputStream(bytes), buildXmlFile,
                bytes.length, buffer);

        tar.close();

        return files.length;
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
