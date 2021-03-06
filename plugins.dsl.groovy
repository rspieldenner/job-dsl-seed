// https://github.com/eclipse/egit-github/tree/master/org.eclipse.egit.github.core
@Grab(group='org.eclipse.mylyn.github', module='org.eclipse.egit.github.core', version='2.1.5')
import org.eclipse.egit.github.core.*
import org.eclipse.egit.github.core.client.*
import org.eclipse.egit.github.core.service.*

GitHubClient client = new GitHubClient()

def githubProperties = new File(GITHUB_PROPERTIES?:System.getenv()['GITHUB_PROPERTIES'])
if (githubProperties.exists()) {
    def props = new Properties()
    githubProperties.withInputStream { 
        stream -> props.load(stream) 
    }
    def gitHubCredentials = props['github.oauth']

    //OAuth2 token authentication
    client.setOAuth2Token(gitHubCredentials)
} else {
    println "Not providing credentials"
}

def orgName = 'nebula-plugins'
def folderName = 'nebula-plugins/'

RepositoryService repoService = new RepositoryService(client);
regex = [/gradle-(.*)-plugin/, /nebula-(\p{Lower}+)$/, /nebula-(.*)-plugin/]
repoService.getOrgRepositories(orgName).findAll { repo -> regex.any { repo.name =~ it } }.each { Repository repo ->
    def repoName = repo.name

    println "Creating jobs for $repoName"

    List<RepositoryBranch> branches = repoService.getBranches(repo)
    def gradleBranches = branches.findAll { it.name.startsWith('gradle-') }
    gradleBranches.collect { RepositoryBranch branch ->
        def nameBase = "${folderName}${repo.name}-${branch.name - 'gradle-'}"
        snapshot(nameBase, repo.description, orgName, repoName, branch.name )
        release(nameBase, repo.description, orgName, repoName, branch.name )
    }

    if (gradleBranches.isEmpty()) {
        // Master
        def nameBase = "${folderName}${repo.name}"
        snapshot(nameBase, repo.description, orgName, repoName, 'master')
        release(nameBase, repo.description, orgName, repoName, 'master')
    }

    // Pull Requests are outside of a specific branch
    pullrequest("${folderName}${repo.name}", repo.description, orgName, repoName, '*' ) // Not sure what the branch should be
}

def base(String repoDesc, boolean linkPrivate = true) {
    job {
        description ellipsize(repoDesc, 255)
        logRotator(60,-1,-1,20)
        wrappers {
            timeout(20)
        }
        jdk('Sun JDK 1.6 (latest)')
        if (linkPrivate) {
            steps {
                shell('''
                if [ ! -d $HOME/.gradle ]; then
                   mkdir $HOME/.gradle
                fi
    
                if [ ! -e $HOME/.gradle/gradle.properties ]; then
                   ln -s /private/netflixoss/gradle/gradle.properties $HOME/.gradle/gradle.properties
                fi
                '''.stripIndent())
            }
        }
        configure { project ->
            project / 'properties' / 'com.cloudbees.jenkins.plugins.PublicKey'(plugin:'cloudbees-public-key@1.1')
            if (linkPrivate) {
                project / buildWrappers / 'com.cloudbees.jenkins.forge.WebDavMounter'(plugin:"cloudbees-forge-plugin@1.6")
            }
        }
        publishers {
            archiveJunit('**/build/test-results/TEST*.xml')
        }
    }
}

def release(nameBase, repoDesc, orgName, repoName, branchName) {
    def job = base(repoDesc)
    job.with {
        name "${nameBase}-release"
        scm {
            github("${orgName}/${repoName}", branchName, 'ssh') {
                //it / userRemoteConfigs / 'hudson.plugins.git.UserRemoteConfig' / credentialsId(gitHubCredentials)
                it / extensions / 'hudson.plugins.git.extensions.impl.LocalBranch' / localBranch(branchName)
            }
        }
        steps {
            gradle('clean release --stacktrace --refresh-dependencies')
        }
    }
}

def snapshot(nameBase, repoDesc, orgName, repoName, branchName) {
    def job = base(repoDesc)
    job.with {
        name "${nameBase}-snapshot"
        scm {
            github("${orgName}/${repoName}", branchName, 'ssh') {
                it / skipTags << 'true'
            }
        }
        triggers {
            cron('@daily')
        }
        steps {
            gradle('clean build snapshot --refresh-dependencies') // TODO a bad nebula-pluging is cached right now, and a refresh workspace won't fix it.
        }
        configure { project ->
            project / triggers / 'com.cloudbees.jenkins.GitHubPushTrigger' / spec
        }
    }
}

def pullrequest(nameBase, repoDesc, orgName, repoName, branchName) {
    def job = base(repoDesc, false)
    job.with {
        name "${nameBase}-pull-requests"
        scm {
            github("${orgName}/${repoName}", branchName, 'ssh') {
                it / skipTags << 'true'
            }
        }
        steps {
            gradle('clean check')
        }
        configure { project ->
            project / triggers / 'com.cloudbees.jenkins.plugins.github__pull.PullRequestBuildTrigger'(plugin:'github-pull-request-build@1.0-beta-2') / spec ()
            project / 'properties' / 'com.cloudbees.jenkins.plugins.git.vmerge.JobPropertyImpl'(plugin:'git-validated-merge@3.6') / postBuildPushFailureHandler(class:'com.cloudbees.jenkins.plugins.git.vmerge.pbph.PushFailureIsFailure')
        }
        publishers {
            // TODO Put pull request number in build number, $GIT_PR_NUMBER
        }
    }
}

String ellipsize(String input, int maxLength) {
  if (input == null || input.length() < maxLength) {
    return input;
  }
  return input.substring(0, maxLength) + '...';
}
