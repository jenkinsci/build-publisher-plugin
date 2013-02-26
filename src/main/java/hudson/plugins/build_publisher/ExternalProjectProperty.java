package hudson.plugins.build_publisher;

import hudson.Util;
import hudson.matrix.Combination;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.maven.MavenModule;
import hudson.model.AbstractProject;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Project;
import hudson.model.ProminentProjectAction;
import hudson.model.Run;
import hudson.model.AbstractBuild;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.LogRotator;
import hudson.util.IOException2;
import net.sf.json.JSONObject;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.taskdefs.Untar;
import org.apache.tools.ant.types.Resource;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import hudson.triggers.TriggerDescriptor;

/**
 * Recieves builds submitted remotely via HTTP.
 *
 * @author dvrzalik@redhat.com
 *
 */
public class ExternalProjectProperty extends JobProperty<Job<?, ?>> implements
        ProminentProjectAction {

    private static final Logger LOGGER = Logger.getLogger(ExternalProjectProperty.class.getName());

    private transient AbstractProject<?,?> project;

    @Override
    public JobPropertyDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ProminentProjectAction getJobAction(Job<?, ?> job) {
        this.project = (AbstractProject) job;
        return this;
    }
    
    public void doCreateConfiguration(StaplerRequest req, StaplerResponse rsp){
        project.checkPermission(Job.CONFIGURE);
        String configuration = req.getParameter("name");
        if(project instanceof MatrixProject){
            MatrixProject matrixProject = (MatrixProject) project;
            MatrixConfiguration config = matrixProject.getItem(configuration);
            if(config==null){
                config = new MatrixConfiguration(matrixProject, Combination.fromString(configuration));
                try{
                    config.save();
                    reloadProject(project);
                    rsp.setHeader("X-configuration-created", config.getName());
                }
                catch(IOException ex){
                    LOGGER.log(Level.WARNING, "Failed to create configuration " + configuration + " for matrix project " + project.getName(), ex);
                }
                
            }
            else{
                LOGGER.log(Level.INFO, "Configuration " + configuration + " for matrix project " + project.getName() + " already exists");
            }
        }
        else{
            LOGGER.log(Level.INFO, "Project " + project.getName() + " is not instance of MatrixProject");
        }
    }

    /**
     * Accepts incoming MavenModule, provided that current project is
     * MavenModuleSet
     */
    public void doAcceptMavenModule(StaplerRequest req, StaplerResponse rsp)
            throws IOException {
        acceptChildProject(req, rsp, project, "modules");
    }
    
    /**
     * Accepts nested project (like maven module or matrix configuration).
     */
    private static void acceptChildProject(StaplerRequest req, StaplerResponse rsp,
            AbstractProject project, String subDir)
            throws IOException {
        project.checkPermission(Job.CONFIGURE);

        String name = req.getParameter("name").trim();
        File modulesDir = new File(project.getRootDir(), subDir);
        File moduleDir = new File(modulesDir, name);
        moduleDir.mkdirs();
        File configFile = new File(moduleDir, "config.xml");

        try {
            FileOutputStream fos = new FileOutputStream(configFile);
            try {
                Util.copyStream(req.getInputStream(), fos);

                project.onLoad(project.getParent(), project.getName());
                
            } finally {
                fos.close();
            }

        } catch (IOException e) {
            LOGGER.severe("Failed to accept child project " + name
                    + " for " + project.getName() + e.getMessage());

            // This is questionable
            // Util.deleteRecursive(moduleDir);

            throw e;
        }
    }

    /**
     * "Collecting basket" for incoming builds.
     */
    public void doAcceptBuild(StaplerRequest req, StaplerResponse rsp)
            throws IOException, InterruptedException {
        project.checkPermission(Job.CONFIGURE);

        // Don't send notifications for old builds
        Set<String> oldBuildIDs = new HashSet<String>();
        for (Run run : project.getBuilds()) {
            oldBuildIDs.add(run.getId());
        }

        File buildsDir = Jenkins.getInstance().getBuildDirFor(project);
        
        //Untar incoming builds unto the build directory
        Untar untar = new Untar();
        untar.setProject(new org.apache.tools.ant.Project());
        untar.add(new InputStreamResource(project.getName(),
                new BufferedInputStream(req.getInputStream())));
        untar.setDest(buildsDir);
        untar.setOverwrite(true);

        
        if (BuildPublisher.DESCRIPTOR.getRemoveTriggers()) {
        	removeTriggers(project);
        }

        String publisherTimezoneID = (String)req.getHeader("X-Publisher-Timezone");
        LOGGER.info("Got remote timezone " + publisherTimezoneID);
        TimeZone publisherTimezone = null;
        String buildId = null;
        String newId = null;
        DateFormat dateFormatter = null;
        DateFormat oldDateFormatter = null;
        if(publisherTimezoneID!=null) {
       	    publisherTimezone = TimeZone.getTimeZone(publisherTimezoneID);
            dateFormatter = Run.getIDFormatter();
            buildId = (String)req.getHeader("X-Build-ID");
            oldDateFormatter = (DateFormat)dateFormatter.clone();
            oldDateFormatter.setTimeZone(publisherTimezone);
            LOGGER.fine("Local timezone " + dateFormatter.getTimeZone());
            LOGGER.fine("Remote timezone " + publisherTimezone);
        }
        
        try {
        	if(publisherTimezone!=null) {
	        	try {
                                LOGGER.fine("Original build time " + oldDateFormatter.parse(buildId));
	        		newId = dateFormatter.format(oldDateFormatter.parse(buildId));
                                LOGGER.fine("New build ID " + newId);
	        	} catch (ParseException e) {
	        		throw new BuildException("Failed to parse buildId", e);
	        	}
        	}
        	
            untar.execute();
            
            if(publisherTimezone!=null) {
	            File oldBuildDir = new File(buildsDir, buildId);
	            File newBuildDir = new File(buildsDir, newId);
	            
	            LOGGER.info("Renaming: " + oldBuildDir.getCanonicalPath() + " to " + newBuildDir.getCanonicalPath());
	            
	            oldBuildDir.renameTo(newBuildDir);
            } else {
                LOGGER.info("No remote timezone found");
            }
            
            //Load incoming builds from disk
            reloadProject(project);

            //Remove publishing status actions (so that they don't confuse users).
            //We don't know which (or how many) builds arrive - need to check them all
            for(Run build: project.getBuilds()) {
                StatusAction statusAction = build.getAction(StatusAction.class);
                if(statusAction != null) {
                    build.getActions().remove(statusAction);
                    build.save();
                }
            }
            
            //Update next build number
            Run lastBuild = project.getLastBuild();
            int nextBuildNumber = (lastBuild != null ? lastBuild.number : 0) + 1;
            project.updateNextBuildNumber(nextBuildNumber);            
            //Add confirmation header
            rsp.addHeader("X-Build-Recieved",project.getName());
            try {
                tidyUp();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cleaning project " + project.getName()
                        + "failed: " + e.getMessage(),e);
            }

        } catch (BuildException e) {
            LOGGER.log(Level.SEVERE, "Failed to read the remote stream "
                    + project.getName() + e.getMessage(),e);
            throw new IOException2("Failed to read the remote stream "
                    + project.getName(), e);
        }
    }

	private void removeTriggers(AbstractProject<?,?> project) throws IOException {
		for(TriggerDescriptor trigger: project.getTriggers().keySet()) {
			project.removeTrigger(trigger);
		}
		
		project.save();
	}

    private void tidyUp() throws IOException, InterruptedException {
        // delete old builds

        //reflect plugin-specific settings
        BuildPublisher publisher = (BuildPublisher) project.getPublishersList().get(BuildPublisher.DESCRIPTOR);
        if (publisher != null) {
            LogRotator rotator = publisher.getLogRotator();
            if (rotator != null) {
                rotator.perform(project);
            } else {
                project.logRotate();
            }
        }

        // keep artifacts of last successful build only
        // (taken from ArtifactArchiver)
        if (project instanceof Project) {
            ArtifactArchiver archiver = project.getPublishersList().get(ArtifactArchiver.class);
            if ((archiver != null) && archiver.isLatestOnly()) {
                AbstractBuild<?, ?> build = project.getLastSuccessfulBuild();
                if (build != null) {
                    while (true) {
                        build = build.getPreviousBuild();
                        if (build == null)
                            break;

                        // remove old artifacts
                        File ad = build.getArtifactsDir();
                        if (ad.exists()) {
                            LOGGER.info("Deleting old artifacts from "
                                    + build.getDisplayName());
                            Util.deleteRecursive(ad);
                        }
                    }
                }
            }
        }

    }

    private static void reloadProject(AbstractProject project)
            throws IOException {
        if (project instanceof MavenModule) {
            project.onLoad(project.getParent(), ((MavenModule) project)
                    .getModuleName().toFileSystemName());
        } else {
            project.onLoad(project.getParent(), project.getName());
        }
    }

    private static class InputStreamResource extends Resource {
        private final InputStream in;

        public InputStreamResource(String name, InputStream in) {
            this.in = in;
            setName(name);
        }

        public InputStream getInputStream() throws IOException {
            return in;
        }
    }

    /*
     * Descriptor, etc..
     */

    public static final ExternalProjectPropertyDescriptor DESCRIPTOR = new ExternalProjectPropertyDescriptor();

    public static class ExternalProjectPropertyDescriptor extends
            JobPropertyDescriptor {

        public ExternalProjectPropertyDescriptor() {
            super(ExternalProjectProperty.class);
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            //This property shall be added only programmaticaly
            return false;
        }

        @Override
        public String getDisplayName() {
            return "Post remote build";
        }

        @Override
        public JobProperty<?> newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return null;
        }

    }
    
    /**
     * Checks if the given project already has this property and posibly adds it (recursive for ItemGroups).
     */
    public static void applyToProject(Job<?,?> job) throws IOException {
        if(job instanceof ItemGroup) {
            for(Object item: ((ItemGroup) job).getItems()) {
                applyToProject((Job /*too optimistic assumption?*/) item);
            }
        }
        
        //Could not use getProperty(...) 
        for (JobProperty prop : job.getProperties().values()) {
            //hem... >:-|
            if (prop.getClass().getName().equals("hudson.plugins.build_publisher.ExternalProjectProperty")) {
                return;
            }
        }
        
        job.addProperty(new ExternalProjectProperty());
    }

    public String getDisplayName() {
        return DESCRIPTOR.getDisplayName();
    }

    public String getIconFileName() {
        return null;
    }

    public String getUrlName() {
        return "postBuild";
    }

}
