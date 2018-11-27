package hudson.plugins.humbug;

import java.util.ArrayList;
import java.util.List;

import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.User;
import hudson.scm.ChangeLogSet;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.FakeChangeLogSCM;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.verifyNew;
import static org.powermock.api.mockito.PowerMockito.when;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Jenkins.class, User.class, HumbugNotifier.class, DescriptorImpl.class,
        AbstractBuild.class, Job.class})
public class HumbugNotifierTest {

    @Mock
    private Jenkins jenkins;

    @Mock
    private Humbug humbug;

    @Mock
    private DescriptorImpl descMock;

    @Mock
    private AbstractBuild build;

    @Mock
    private AbstractBuild previousBuild;

    @Mock
    private Job job;

    @Captor
    private ArgumentCaptor<String> streamCaptor;

    @Captor
    private ArgumentCaptor<String> topicCaptor;

    @Captor
    private ArgumentCaptor<String> messageCaptor;

    @Before
    public void setUp() throws Exception {
        PowerMockito.whenNew(Humbug.class).withAnyArguments().thenReturn(humbug);
        PowerMockito.mockStatic(Jenkins.class);
        when(Jenkins.getInstance()).thenReturn(jenkins);
        PowerMockito.mockStatic(User.class);
        when(User.get(anyString())).thenAnswer(new Answer<User>() {
            @Override
            public User answer(InvocationOnMock invocation) throws Throwable {
                String arg = (String) invocation.getArguments()[0];
                User userMock = PowerMockito.mock(User.class);
                when(userMock.getDisplayName()).thenReturn(arg);
                return userMock;
            }
        });
        when(descMock.getUrl()).thenReturn("zulipUrl");
        when(descMock.getEmail()).thenReturn("jenkins-bot@zulip.com");
        when(descMock.getApiKey()).thenReturn("secret");
        when(descMock.getStream()).thenReturn("defaultStream");
        when(descMock.getTopic()).thenReturn("defaultTopic");
        PowerMockito.whenNew(DescriptorImpl.class).withAnyArguments().thenReturn(descMock);
        when(build.getParent()).thenReturn(job);
        when(build.getDisplayName()).thenReturn("#1");
        when(build.getUrl()).thenReturn("job/TestJob/1");
        when(build.hasChangeSetComputed()).thenReturn(true);
        when(build.getChangeSet()).thenReturn(ChangeLogSet.createEmpty((Run<?, ?>) build));
        when(job.getDisplayName()).thenReturn("TestJob");
        when(job.getUrl()).thenReturn("job/TestJob");
    }

    @Test
    public void testShouldUseDefaults() throws Exception {
        HumbugNotifier notifier = new HumbugNotifier();
        notifier.perform(build, null, null);
        verifyNew(Humbug.class).withArguments("zulipUrl", "jenkins-bot@zulip.com", "secret");
        verify(humbug).sendStreamMessage(streamCaptor.capture(), topicCaptor.capture(), messageCaptor.capture());
        assertEquals("Should use default stream", "defaultStream", streamCaptor.getValue());
        assertEquals("Should use default topic", "defaultTopic", topicCaptor.getValue());
        assertEquals("Message should be successful build", "**Project: **TestJob : **Build: **#1: **SUCCESS** :check_mark:", messageCaptor.getValue());
        // Test with blank values
        reset(humbug);
        notifier.setStream("");
        notifier.setTopic("");
        notifier.perform(build, null, null);
        verify(humbug).sendStreamMessage(streamCaptor.capture(), topicCaptor.capture(), messageCaptor.capture());
        assertEquals("Should use default stream", "defaultStream", streamCaptor.getValue());
        assertEquals("Should use default topic", "defaultTopic", topicCaptor.getValue());
    }

    @Test
    public void testFailedBuild() throws Exception {
        HumbugNotifier notifier = new HumbugNotifier();
        when(build.getResult()).thenReturn(Result.FAILURE);
        notifier.perform(build, null, null);
        verify(humbug).sendStreamMessage(streamCaptor.capture(), topicCaptor.capture(), messageCaptor.capture());
        assertEquals("Message should be failed build", "**Project: **TestJob : **Build: **#1: **FAILURE** :x:", messageCaptor.getValue());
    }

    @Test
    public void testShouldUseProjectConfig() throws Exception {
        HumbugNotifier notifier = new HumbugNotifier();
        notifier.setStream("projectStream");
        notifier.setTopic("projectTopic");
        notifier.perform(build, null, null);
        verify(humbug).sendStreamMessage(streamCaptor.capture(), topicCaptor.capture(), messageCaptor.capture());
        assertEquals("Should use project stream", "projectStream", streamCaptor.getValue());
        assertEquals("Should use topic stream", "projectTopic", topicCaptor.getValue());
    }

    @Test
    public void testShouldUseProjectNameAsTopic() throws Exception {
        try {
            HumbugNotifier notifier = new HumbugNotifier();
            when(descMock.getTopic()).thenReturn("");
            Whitebox.setInternalState(HumbugNotifier.class, descMock);
            notifier.perform(build, null, null);
            verify(humbug).sendStreamMessage(streamCaptor.capture(), topicCaptor.capture(), messageCaptor.capture());
            assertEquals("Topic should be project display name", "TestJob", topicCaptor.getValue());
            assertEquals("Message should not contain project name", "**Build: **#1: **SUCCESS** :check_mark:", messageCaptor.getValue());
        } finally {
            // Be sure to return global setting back to original setup so other tests dont fail
            when(descMock.getTopic()).thenReturn("defaultTopic");
            Whitebox.setInternalState(HumbugNotifier.class, descMock);
        }
    }

    @Test
    public void testChangeLogSet() throws Exception {
        List<FakeChangeLogSCM.EntryImpl> changes = new ArrayList<>();
        changes.add(createChange("Author 1", "Short Commit Msg"));
        changes.add(createChange("Author 2", "This is a very long commit message that will get truncated in the Zulip message"));
        FakeChangeLogSCM.FakeChangeLogSet changeLogSet = new FakeChangeLogSCM.FakeChangeLogSet(build, changes);
        when(build.getChangeSet()).thenReturn(changeLogSet);
        HumbugNotifier notifier = new HumbugNotifier();
        notifier.perform(build, null, null);
        verify(humbug).sendStreamMessage(streamCaptor.capture(), topicCaptor.capture(), messageCaptor.capture());
        assertEquals("Message should contain change log", "**Project: **TestJob : **Build: **#1: **SUCCESS** :check_mark:\n" +
                "\n" +
                "Changes since last build:\n" +
                "\n" +
                "* `Author 1` Short Commit Msg\n" +
                "* `Author 2` This is a very long commit message that will g...", messageCaptor.getValue());
    }

    @Test
    public void testJenkinsUrl() throws Exception {
        try {
            HumbugNotifier notifier = new HumbugNotifier();
            when(descMock.getHudsonUrl()).thenReturn("JenkinsUrl");
            Whitebox.setInternalState(HumbugNotifier.class, descMock);
            notifier.perform(build, null, null);
            verify(humbug).sendStreamMessage(streamCaptor.capture(), topicCaptor.capture(), messageCaptor.capture());
            assertEquals("Message should contain links to Jenkins", "**Project: **[TestJob](JenkinsUrl/job/TestJob) : **Build: **[#1](JenkinsUrl/job/TestJob/1): **SUCCESS** :check_mark:", messageCaptor.getValue());
        } finally {
            // Be sure to return global setting back to original setup so other tests dont fail
            when(descMock.getHudsonUrl()).thenReturn("");
            Whitebox.setInternalState(HumbugNotifier.class, descMock);
        }
    }

    @Test
    public void testSmartNotify() throws Exception {
        try {
            HumbugNotifier notifier = new HumbugNotifier();
            when(descMock.isSmartNotify()).thenReturn(true);
            Whitebox.setInternalState(HumbugNotifier.class, descMock);
            // If there was no previous build, notification should be sent no matter the result
            reset(humbug);
            when(build.getPreviousBuild()).thenReturn(null);
            when(build.getResult()).thenReturn(Result.SUCCESS);
            notifier.perform(build, null, null);
            when(build.getResult()).thenReturn(Result.FAILURE);
            notifier.perform(build, null, null);
            verify(humbug, times(2)).sendStreamMessage(anyString(), anyString(), anyString());
            // If the previous build was a failure, notification should be sent no matter what
            reset(humbug);
            when(build.getPreviousBuild()).thenReturn(previousBuild);
            when(previousBuild.getResult()).thenReturn(Result.FAILURE);
            when(build.getResult()).thenReturn(Result.SUCCESS);
            notifier.perform(build, null, null);
            when(build.getResult()).thenReturn(Result.FAILURE);
            notifier.perform(build, null, null);
            verify(humbug, times(2)).sendStreamMessage(anyString(), anyString(), anyString());
            // If the previous build was a success, notification should be sent only for failed builds
            reset(humbug);
            when(build.getPreviousBuild()).thenReturn(previousBuild);
            when(previousBuild.getResult()).thenReturn(Result.SUCCESS);
            when(build.getResult()).thenReturn(Result.SUCCESS);
            notifier.perform(build, null, null);
            when(build.getResult()).thenReturn(Result.FAILURE);
            notifier.perform(build, null, null);
            verify(humbug, times(1)).sendStreamMessage(anyString(), anyString(), anyString());
        } finally {
            // Be sure to return global setting back to original setup so other tests dont fail
            when(descMock.isSmartNotify()).thenReturn(false);
            Whitebox.setInternalState(HumbugNotifier.class, descMock);
        }
    }

    private FakeChangeLogSCM.EntryImpl createChange(String author, String msg) {
        return new FakeChangeLogSCM.EntryImpl().withAuthor(author).withMsg(msg);
    }

}
