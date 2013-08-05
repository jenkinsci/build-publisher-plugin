/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hudson.plugins.build_publisher;


import hudson.matrix.AxisList;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.plugins.build_publisher.BuildPublisher.BuildPublisherDescriptor;
import hudson.tasks.Shell;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import jenkins.model.Jenkins;
import junit.framework.TestCase;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.powermock.reflect.Whitebox;
  

/**
 *
 * @author lucinka
 */
public class PublisherThreadTest {
    

  @Rule
  public JenkinsRule publicRule = new JenkinsPublisherRule();
  
  @Rule
  public JenkinsRule internalRule = new JenkinsPublisherRule();
  
  private Jenkins publicJenkins;
  
  private Jenkins jenkins;
  
  private URL publicJenkinsURL;
  
  private URL jenkinsURL;
  
  private BuildPublisherDescriptor internalDescriptor;
  
  private BuildPublisher internalPublisher;
  
  @Before
  public void preparePublicJenkins() throws IOException {
    publicJenkins = publicRule.jenkins;
    publicJenkinsURL = publicRule.getURL();
    this.jenkins = internalRule.jenkins;
    jenkinsURL = internalRule.getURL();
    switchToInternalJenkins();
    createBuildPublishers();
    publicJenkins.setCrumbIssuer(null);
    jenkins.setCrumbIssuer(null);
  }
  
  public void switchToPublicJenkins() {
      Whitebox.setInternalState(publicJenkins, "theInstance", publicJenkins, Jenkins.class);     
  }
  
  public void switchToInternalJenkins() {
      Whitebox.setInternalState(publicJenkins, "theInstance", jenkins, Jenkins.class);
  }
  
  /**
   * Test if a free style job with name which needs to be encoded is published
   * 
   * @throws IOException
   * @throws InterruptedException
   * @throws ExecutionException 
   */
  @Test
  public void testPublishFreeStypeProjectWithEncodingNeed() throws IOException, ExecutionException, InterruptedException  {
    switchToInternalJenkins();    
    FreeStyleProject project = internalRule.createFreeStyleProject("~`123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}");   
    project.getBuildersList().add(new Shell("echo hello")); 
    project.getPublishersList().add(internalPublisher); 
    FreeStyleBuild build = project.scheduleBuild2(0).get();
    switchToPublicJenkins();
    TestCase.assertTrue("A Build has not been published.",waitForResult(null,project.getName(), build.getNumber(), 60));
  }
  
  /**
   * Test if a matrix job with name which needs to be encoded is published
   * 
   * @throws IOException
   * @throws InterruptedException
   * @throws ExecutionException 
   */
   @Test
  public void testPublishMatrixProjectWithEncodingNeed() throws IOException, InterruptedException, ExecutionException, NoSuchFieldException, IllegalArgumentException  {
    switchToInternalJenkins();
    MatrixProject project = internalRule.createMatrixProject("~`1123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}");    
    project.getBuildersList().add(new Shell("echo hello")); 
    project.getPublishersList().add(internalPublisher); 
    TextAxis axis = new TextAxis("user", "~`!1@2#3$4%5^6&7*8(9)0_-=}]{[Poiuytrewqasdfghjkl:;\"'||&&zxcv","bnm<>./?");
    AxisList list = new AxisList();
    list.add(axis);
    project.setAxes(list);
    MatrixBuild build = project.scheduleBuild2(0).get();
    switchToPublicJenkins();
    TestCase.assertTrue("A Build of matrix project ~`1123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{} has not been published.",waitForResult(null, project.getName(), build.getNumber(), 60));
    for(MatrixConfiguration configuration: project.getActiveConfigurations()){
        TestCase.assertTrue("A Build of matrix configuration "+ configuration.getName() + " has not been published.",waitForResult(project.getName(), configuration.getName(), build.getNumber(), 60));
    }
  }
  
   /*
    * Test if given build exists with waiting interval
    */
   public boolean waitForResult(String parentMatrixJobName, String jobName, int buildNumber, int attemptsCount) throws IOException, InterruptedException{
    while(attemptsCount!=0){
        Job job = null;
        if(parentMatrixJobName==null){
            job = (Job) publicJenkins.getItem(jobName);
        }
        else {
            MatrixProject matrixJob = (MatrixProject) (Job) publicJenkins.getItem(parentMatrixJobName);
            if(matrixJob==null){
                Thread.sleep(1000);
                attemptsCount--;
                continue;
            }
            job = matrixJob.getItem(jobName);
        }
        if(job==null || job.getBuildByNumber(buildNumber)==null){
            Thread.sleep(1000);
            attemptsCount--;
            continue;
        }
        return true;    
    }
    return false;
   }
  
  public void createBuildPublishers(){
      internalPublisher = new BuildPublisher();
      internalDescriptor = (BuildPublisherDescriptor) internalPublisher.getDescriptor();
      HudsonInstance instances[] = new HudsonInstance[1];
      HudsonInstance instance = new HudsonInstance("Public jenkins", publicJenkinsURL.toString(), null, null, null);
      instances[0]=instance;
      internalDescriptor.setPublicInstances(instances);
  }
    
}

