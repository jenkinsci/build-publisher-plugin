package hudson.plugins.build_publisher;

import hudson.XmlFile;
import hudson.maven.MavenBuild;
import hudson.maven.MavenModule;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.maven.MavenReporter;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Build;
import hudson.model.Project;
import hudson.model.StreamBuildListener;
import hudson.tasks.Publisher;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.dom4j.tree.DefaultElement;

import java.io.IOException;
import java.util.logging.Level;

/**
 * {@link Thread} responsible for reading the queue and sending builds.
 */
public class PublisherThread extends Thread {
    
    // What's the point of locking on Boolean? - KK
    // None. - David
    
    private AbstractBuild currentRequest = null;

    private volatile ThreadState state = ThreadState.IDLE;

    /**
     * The public Hudson that this thread is publishing to.
     */
    private final HudsonInstance hudsonInstance;

    /**
     * @param hudsonInstance
     */
    PublisherThread(HudsonInstance hudsonInstance) {
        super("Hudson - Build-Publisher Thread for "+hudsonInstance.getName());
        this.hudsonInstance = hudsonInstance;
    }

    @Override
    public void run() {
        try {
            while (true) {
                state = ThreadState.IDLE;
                currentRequest = hudsonInstance.nextRequest();

                state = new ThreadState.Publishing(currentRequest);
                
                StatusAction.setBuildStatusAction(currentRequest,
                        new StatusInfo(StatusInfo.State.INPROGRESS,
                                "Build is being transmitted", hudsonInstance
                                        .getName(), null));
                

                try {
                    // Proceed transmission
                    synchronizeProjectSettings(hudsonInstance.getUrl(),
                            currentRequest.getProject());
                    hudsonInstance.buildTransmitter.sendBuild(currentRequest,
                            currentRequest.getProject(), hudsonInstance);

                 
                    sendMailNotification(currentRequest);
                    // Notify about success
                    HudsonInstance.LOGGER.info("Build #"
                            + currentRequest.getNumber() + " of project "
                            + currentRequest.getProject().getDisplayName()
                            + " was published.");

                    hudsonInstance
                            .removeRequest(
                                    currentRequest,
                                    new StatusInfo(
                                            StatusInfo.State.SUCCESS,
                                            "Build transmission was successfully completed",
                                            hudsonInstance.getName(), null));                       
                   
                } catch (Exception e) {
                    // Something's wrong. Let's wait awhile and try again.
                    HudsonInstance.LOGGER.log(Level.WARNING,"Error during build transmission: "+e.getMessage(),e);
                    StatusAction.setBuildStatusAction(currentRequest,
                            new StatusInfo(StatusInfo.State.FAILURE_PENDING,
                                    "Error during build publishing", hudsonInstance
                                            .getName(), e));
                    hudsonInstance.postponeRequest(currentRequest);

                    // TODO make this configurable
                    final long timeout = System.currentTimeMillis() + 1000*60*10;
                    state = new ThreadState.ErrorRecoveryWait(timeout,currentRequest,e);

                    try {
                        while(System.currentTimeMillis() < timeout)
                            Thread.sleep(timeout-System.currentTimeMillis());
                    } catch (InterruptedException e1) {
                        // note that this also happens when the administrator manually forced a retry,
                        // ignoring timeout
                        HudsonInstance.LOGGER.log(Level.SEVERE,"Build oublisher thread was interrupted",e1);
                    }
                }
            }
        } catch(Error e) {
            state = new ThreadState.Dead(e);
            throw e;
        } catch(RuntimeException e) {
            state = new ThreadState.Dead(e);
            throw e;
        }
    }

    /**
     * Gets an immutable object representing what this thread is currently doing.
     *
     * @return
     *      never null.
     */
    public ThreadState getCurrentState() {
        return state;
    }

    private void sendMailNotification(AbstractBuild request) {

        String recipients = null;
        if (request instanceof Build) {
            Build build = (Build) request;
            Publisher publisher = ((Project) build.getProject())
                    .getPublisher(BuildPublisher.DESCRIPTOR);
            if (publisher instanceof BuildPublisher) {
                recipients = ((BuildPublisher) publisher)
                        .getNotificationRecipients();
            }
        } else if (request instanceof MavenBuild) {
            MavenBuild build = (MavenBuild) request;
            MavenReporter reporter = build.getProject().getReporters().get(
                    MavenBuildPublisher.DESCRIPTOR);
            if (!(reporter instanceof MavenBuildPublisher)) {
                reporter = build.getProject().getParent().getReporters().get(
                        MavenBuildPublisher.DESCRIPTOR);
            }

            if (reporter instanceof MavenBuildPublisher) {
                recipients = ((MavenBuildPublisher) reporter)
                        .getNotificationRecipients();
            }
        }

        if ((recipients == null) || recipients.trim().length() == 0) {
            return;
        }

        // because of our custom modifications we can't use MailSender
        // TODO remove this duplicity
        try {
            new MailSender2(recipients, true, false, false, hudsonInstance
                    .getUrl()).execute(request, new StreamBuildListener(
                    System.out));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Cancels the transmission of the given build.
     * If the build is being transmitted, a cancellation is attempted.
     */
    
    /* 
    public void abortTrasmission(AbstractBuild request) {
        // FIXME: this is broken in so many levels
        // first the synchronization is wrong, and
        // then request==null check is wrong, because currentRequest can be null,
        // in which case it would cause NPE.
        // And then, there's no guarantee that a buildTransmitter is doing the work when
        // this method is invoked, so the cancellation could fail to cancel.
        //
        // in general, I don't think it's possible to cleanly cancel a transmission that's in progress.
        // the best you can do is to interrupt the thread and hope the thread would take notice.
        // -KK
      
        // I still believe it is possible. Anyway, let's disable it for now. - DV
     
            
        HudsonInstance.LOGGER.info("Publishing of Build #"
                + currentRequest.getNumber() + " of project "
                + currentRequest.getProject().getDisplayName()
                + " was canceled.");
        hudsonInstance.buildTransmitter.abortTransmission();

        hudsonInstance.removeRequest(request, new StatusInfo(
                StatusInfo.State.INTERRUPTED,
                "Build transmission was aborted by user", hudsonInstance
                        .getName(), null));
     
    }*/

    /**
     * Creates new project on the public server in case it doesn't already exist
     * and submits local config.xml.
     */
    private void synchronizeProjectSettings(String publicHudson,
            AbstractProject project) throws IOException {

        assertUrlExists(publicHudson);

        if (project instanceof MavenModule) {
            MavenModuleSet parentProject = ((MavenModule) project).getParent();
            createOrSynchronize(publicHudson, parentProject);

            String parentURL = publicHudson + "job/" + parentProject.getName();

            MavenModuleSetBuild parentBuild = parentProject.getLastBuild();
            if (!urlExists(parentURL + "/" + parentBuild.getNumber())) {
                hudsonInstance.publishNewBuild(parentBuild);
            }

            String moduleModuleSystemName = ((MavenModule) project)
                    .getModuleName().toFileSystemName();

            if (!urlExists(parentURL + "/" + moduleModuleSystemName)) {
                submitConfig(parentURL + "/postBuild/acceptMavenModule?name="
                        + moduleModuleSystemName, project);
            }

        } else {
            createOrSynchronize(publicHudson, project);
        }

    }

    private void createOrSynchronize(String publicHudson,
            AbstractProject project) throws IOException {

        String projectURL = publicHudson + "job/" + project.getName();
        System.out.println("Project url:"+projectURL);
        String submitConfigUrl = null;

        if (!urlExists(projectURL)) {
            // if the project doesn't exist, create it
            submitConfigUrl = publicHudson + "createItem?name="
                    + project.getName();
        } else {
            // otherwise just synchronize config file
            submitConfigUrl = projectURL + "/postBuild/acceptConfig";
        }

        submitConfig(submitConfigUrl, project);
    }

    private void submitConfig(String submitConfigUrl, AbstractProject project)
            throws IOException {

        String configXML = updateProjectXml(project);
//        String encodedURL = HTTPBuildTransmitter.encodeURI(submitConfigUrl);
        PostMethod method = new PostMethod();
        method.setURI(new org.apache.commons.httpclient.URI(submitConfigUrl,
                                false));
        method.setRequestEntity(new StringRequestEntity(configXML, "text/xml",
                "utf-8"));

        int responseCode = executeMethod(method);
        if (responseCode >= 400) {
            throw new HttpException(submitConfigUrl
                    + ": Server responded with status " + responseCode);
        }
    }

    private void assertUrlExists(String url) throws IOException {
        if (!urlExists(url)) {
            // wrong address, give up
            throw new HttpException(url + ": URL doesn't exist");
        }
    }

    /* Project config changes */
    private String updateProjectXml(AbstractProject project) throws IOException {
        XmlFile xmlFile = project.getConfigFile();
        SAXReader reader = new SAXReader();
        Document document;
        try {
            document = reader.read(xmlFile.getFile());
            Element root = document.getRootElement();

            // We don't want notifications to be sent twice
            Node mailerNode = document
                    .selectSingleNode("//publishers/hudson.tasks.Mailer");
            if (mailerNode != null) {
                mailerNode.detach();
            } else {
                mailerNode = document
                        .selectSingleNode("//reporters/hudson.maven.reporters.MavenMailer");
                if (mailerNode != null) {
                    mailerNode.detach();
                }
            }

            Node reporterNode = document
                    .selectSingleNode("//publishers/hudson.plugins.build__publisher.MavenBuildPublisher");
            if (reporterNode != null) {
                reporterNode.detach();
            } else {
                Node publisherNode = document
                        .selectSingleNode("//publishers/hudson.plugins.build__publisher.BuildPublisher");
                if (publisherNode != null) {
                    publisherNode.detach();
                }
            }

            // add capability to accept incoming builds
            Node property = document
                    .selectSingleNode("//properties/hudson.plugins.build_publisher.ExternalProjectProperty");
            if (property == null) {
                Element properties = root.element("properties");
                if (properties == null) {
                    properties = new DefaultElement("properties");
                    root.add(properties);
                }
                properties
                        .addElement("hudson.plugins.build_publisher.ExternalProjectProperty");
            }

            // remove triggers
            Element triggers = root.element("triggers");
            if (triggers != null) {
                triggers.detach();
            }

            return document.asXML();

        } catch (DocumentException e) {
            return xmlFile.asString();
        }
    }

    private boolean urlExists(String url) throws IOException {

        PostMethod method = new PostMethod();
        method.setURI(new org.apache.commons.httpclient.URI(url,
                                false));
        int statusCode = executeMethod(method);
        method.releaseConnection();
        if (statusCode < 300) {
            return true;
        } else if ((statusCode == 400) || (statusCode == 404)) {
            return false;
        } else {
            throw new HttpException(method.getURI() + ": server responded with status "
                    + statusCode + "\n" + method.getStatusLine());
        }
    }

    /* shortcut */
    private int executeMethod(HttpMethodBase method) throws IOException {

        return HTTPBuildTransmitter.executeMethod(method, this.hudsonInstance);
    }

}