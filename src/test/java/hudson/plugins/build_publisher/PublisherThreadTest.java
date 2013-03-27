/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hudson.plugins.build_publisher;


import hudson.Util;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.plugins.build_publisher.BuildPublisher.BuildPublisherDescriptor;
import hudson.tasks.Shell;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import junit.framework.TestCase;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
  

/**
 *
 * @author lucinka
 */
public class PublisherThreadTest {
    

  @Rule
  public JenkinsRule j = new JenkinsRule();
  
  private Process process;
  
  private int port;
  
  @Before
  public void preparePublicJenkins() throws UnknownHostException, IOException, InterruptedException{
    runPublishJenkins();
    URI uri = new URI("http://localhost:"+port + "/",false);
    HttpClient client = new HttpClient();
    GetMethod method = new GetMethod();
    method.setURI(uri);
    if(!waitForResult(method, client, 120))  
        TestCase.fail("Publish jenkins did not start after 2 minutes waiting");
  }

  /**
   * Test if a free style job with name which needs to be encoded is published
   * 
   * @throws IOException
   * @throws InterruptedException
   * @throws ExecutionException 
   */
  @Test
  public void testPublishingFreeStypeProjectWithEncodingNeed() throws IOException, InterruptedException, ExecutionException  {
    FreeStyleProject project = j.createFreeStyleProject("~`123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}");   
    project.getBuildersList().add(new Shell("echo hello")); 
    project.getPublishersList().add(createBuildPublisher()); 
    FreeStyleBuild build = project.scheduleBuild2(0).get();
    GetMethod method = new GetMethod("http://localhost:"+port + "/job/" + Util.rawEncode("~`123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}")+"/1/"); 
    HttpClient client = new HttpClient();
    TestCase.assertTrue("Build has not been published.",waitForResult(method, client, 60));
  }
  
  /**
   * Test if a matrix job with name which needs to be encoded is published
   * 
   * @throws IOException
   * @throws InterruptedException
   * @throws ExecutionException 
   */
   @Test
  public void testPublishingMatrixProjectWithEncodingNeed() throws IOException, InterruptedException, ExecutionException  {
    MatrixProject project = j.createMatrixProject("~`1123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}");    
    project.getBuildersList().add(new Shell("echo hello")); 
    project.getPublishersList().add(createBuildPublisher()); 
    TextAxis axis = new TextAxis("user", "~`!1@2#3$4%5^6&7*8(9)0_-=}]{[Poiuytrewqasdfghjkl:;\"'||&&zxcv","bnm<>./?");
    AxisList list = new AxisList();
    list.add(axis);
    project.setAxes(list);
    MatrixBuild build = project.scheduleBuild2(0).get();
    GetMethod method = new GetMethod("http://localhost:"+port + "/job/" + Util.rawEncode("~`1123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}") + "/1"); 
    HttpClient client = new HttpClient();
    TestCase.assertTrue("Build of matrix project ~`1123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{} has not been published.",waitForResult(method, client, 60));
    method = new GetMethod("http://localhost:"+port + "/job/" + Util.rawEncode("~`1123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}") + "/user=" + Util.rawEncode("~`!1@2#3$4%5^6&7*8(9)0_-=}]{[Poiuytrewqasdfghjkl:;\"'||&&zxcv") + "/1");    
    TestCase.assertTrue("Build of matrix configuration ~`!1@2#3$4%5^6&7*8(9)0_-=}]{[Poiuytrewq asdfghjkl:;\"'||&& zxcv has not been published.",waitForResult(method, client, 60));
    method = new GetMethod("http://localhost:"+port + "/job/" + Util.rawEncode("~`1123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}") + "/user=" + Util.rawEncode("bnm<>./?") + "/1");
    TestCase.assertTrue("Build of matrix configuration bnm<>./? has not been published.",waitForResult(method, client, 60));
 }
  
   /*
    * Test if given page exists with waiting interval
    */
   public boolean waitForResult(GetMethod method, HttpClient client, int attemptsCount) throws IOException, InterruptedException{
    while(attemptsCount!=0){
        try{
            client.executeMethod(method);
            if(method.getStatusCode()==200)
                return true;
         }
         catch(Throwable e){
             //waiting even if it return error
         }
            Thread.sleep(1000);
            attemptsCount--;
    }
    return false;
   }
  
  public BuildPublisher createBuildPublisher(){
      BuildPublisher publisher = new BuildPublisher();
      BuildPublisherDescriptor descriptor = (BuildPublisherDescriptor) publisher.getDescriptor();
      HudsonInstance instances[] = new HudsonInstance[1];
      HudsonInstance instance = new HudsonInstance("Public jenkins", "http://localhost:"+port + "/", null, null);
      instances[0]=instance;
      descriptor.setPublicInstances(instances);
      return publisher;
  }
  
  /*
   * Run public Jenkins instance in other process
   */
  public void runPublishJenkins() throws UnknownHostException, IOException{
      ServerSocket s = new ServerSocket(0);
      port = s.getLocalPort();
      s.close();
      String home = j.createTmpDir().getAbsolutePath();
      copyPlugin(home);
      ProcessBuilder b = new ProcessBuilder();
      process = Runtime.getRuntime().exec("mvn hpi:run -DskipTests=true -Djetty.port=" + port  +" -DJENKINS_HOME=" + home);

  }
  
  
  @After
  public void stopPublisher(){
      if(process!=null){
          process.destroy();
      }
     
  }
  
  /*
   * Copy Build publisher publin into plugins of public Jenkins instance
   */
  public void copyPlugin(String home) throws IOException{
       FileUtils.copyURLToFile(new File("target/build-publisher.hpi").toURI().toURL(), new File(home, "plugins/build-publisher.hpi"));
  }
  
    
}
