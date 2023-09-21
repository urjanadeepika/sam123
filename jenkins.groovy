import com.tikal.jenkins.plugins.multijob.*
import groovy.json.JsonOutput
import hudson.*
import hudson.model.*
import hudson.plugins.git.*
import hudson.slaves.*
import hudson.tasks.*

git_server = "https://code.airtelworld.in:7990/bitbucket"
slackNotificationURL = "**TODO**"
project = ""
jobName = ""
message = "message"
failedTestsString = "-N/A-"
isStaticUI = false
image_id = false
REGISTRY_CREDENTIALS = ""
REGISTRY_URL = ""

// ------------------------------------------------------------------------------------------------------------------------
// [START] JSON Parse
// ------------------------------------------------------------------------------------------------------------------------
@NonCPS
def jsonParse(def json) {
    new groovy.json.JsonSlurperClassic().parseText(json)
}

def yamlParse(yaml_path) {
    parsed_yaml = readYaml file: yaml_path
    return parsed_yaml
}
// ------------------------------------------------------------------------------------------------------------------------
// [START] JSON Parse
// ------------------------------------------------------------------------------------------------------------------------

// ------------------------------------------------------------------------------------------------------------------------
// [START] Get GIT information
// ------------------------------------------------------------------------------------------------------------------------
def getGitAuthor() {
    def commit = sh(returnStdout: true, script: 'git rev-parse HEAD')
    author = sh(returnStdout: true, script: "git --no-pager show -s --format='%an' ${commit}").trim()
    return author
}

def getCommitLOG() {
    return sh(returnStdout: true, script: 'git log $(git tag | sort -r | head -2 | tail -1)..$(git tag | sort -r | head -1) --pretty=format:"[%ad] %s By: %an" | grep -v "Merge"')
}

def getLastCommitHash() {
    return sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
}

def validateGitVersionReleaseTag(ver){
    try{
        sh("git fetch --prune origin '+refs/tags/*:refs/tags/*'")
        tag_hash = sh(returnStdout: true, script: "git rev-parse -q --verify 'refs/tags/v${ver}' || :").trim()
    }
    catch(err){
        throw err
    }

    if (tag_hash?.trim()){

        return true
    }
    else{
        return false
    }
}

def populateGlobalVariables() {
    jobName = "${env.JOB_NAME}"

    project = "${env.JOB_NAME}".tokenize("/")[-2];
    lob = "${env.JOB_NAME}".tokenize("/")[-4]

    if (project.contains("-ui-") || (project.contains("-fe-") && env.BRANCH_NAME.toLowerCase().matches("master|release_beta"))) { isStaticUI = true}
    else { isStaticUI = false }

    if (project.contains("-py-")) { isPython = true}
    else { isPython = false }

    if (env.BRANCH_NAME.toLowerCase().matches("performance")) { is_performance = true }
    else { is_performance = false }

    project_base = "${env.JOB_NAME}".tokenize("/")[-3];

    jobName = jobName.getAt(0..(jobName.indexOf('/') - 1))

    getGitAuthor()
}
// ------------------------------------------------------------------------------------------------------------------------
// [END] Get GIT information
// ------------------------------------------------------------------------------------------------------------------------

def code_quality() {
         if (env.BRANCH_NAME.toLowerCase().matches("master") && (lob == "AirtelWork" || lob == "AIRL") && image_id == "false") {
          withSonarQubeEnv('SonarQube') {
           try {
               sh "make testsonar"
               def getURL = readProperties file: './target/sonar/report-task.txt'
               def sonarqubeURL = "${getURL['dashboardUrl']}"
               echo "${sonarqubeURL}"
               sh("sed -i 's#__SONARURL__#${sonarqubeURL}#' ./sandbox/config/DeploymentReport.html")
           }
           catch (err) {
                echo "Sonar report is not available"
           }
         }
       }
       else if ((env.BRANCH_NAME.toLowerCase().matches("develop|releasev2|releasev3|integration") || env.CHANGE_TARGET == "master") && !isStaticUI && (lob == "AirtelWork" || lob == "AIRL" ))
         {
           try {
                echo "Executing checkstyle"
                sh "make checkstyle"
                recordIssues(tools: [checkStyle(reportEncoding: 'UTF-8')])
                foundErrors = sh(script: "cat checkstyle-result.xml | grep 'severity=\"error\"' | wc -l",returnStdout: true).trim()
                echo "Checkstyle errors are ${foundErrors}"
           }
           catch (err) {
                echo "Error Executing checkstyle"
           }
           finally {
            if (!isStaticUI || !isPython){
              if ("${project}" == "task-manager-service" ) {
                //checkstyleLimit = "${app_filler.checkstyleLimit}"
                if ("${foundErrors}" > '92500' ) {
                  error("Build failed because of ${foundErrors} errors in static code analysis which is more than set limit of 92000 errors")
                }
              }
            }
          }
        }
       else if (env.BRANCH_NAME.toLowerCase().matches("develop|master") && isStaticUI && lob == "AirtelWork")
       {
         try {
           echo "running sonar"
           sh "make testsonar"
         }
         catch (err) {
                echo "No sonar rule for this project "
           }
       }
       else{
            echo "Reports are already generated"
         }
}

def notifytelegram(unicode,phase,status,service,version,environment) {
  try {
    date = sh(script: 'date', returnStdout: true).trim()
    next_deployment_date = sh(script: """date -d '45 minutes'""", returnStdout: true).trim()
    if(phase == '100') {
       custom_message = "Deployment complete!"
    } else {
       custom_message = "The deployment to next stage will happen at ${next_deployment_date}"
    }
    sh "curl -s -X POST https://api.telegram.org/bot5322802961:AAEhK7qTGM8-_cfXJpeVioS_qwYQNASbgr8/sendMessage -d 'chat_id=-1001164910468&text=${unicode} %30%E2%83%A3 *AlertName*: ${service} deployment ${phase} %\n*Component*: ${service}\n*Version*: ${version}\n*Status*: ${status}\n*Deployment Time*: ${date}\n*Deployment Remark*: ${custom_message}\n*Owners*: @meet190895,@rakebhag,@ashwani\\_devops,@vipin9458,@pdcdev,@Karishmasingh12,@surajairtel,@Rohit9011,@amit002\\_pec\n*Dashboard*: http://opsanalytics.airtel.com/d/DamkN2g4k/microservices-monitoring-aw?orgId=1%26viewPanel=33&parse_mode=Markdown'"
    sh "curl -s -X POST https://api.telegram.org/bot5322802961:AAEhK7qTGM8-_cfXJpeVioS_qwYQNASbgr8/sendMessage -d 'chat_id=-687666839&text=${unicode} %30%E2%83%A3 *AlertName*: ${service} deployment ${phase} %\n*Component*: ${service}\n*Version*: ${version}\n*Status*: ${status}\n*Deployment Time*: ${date}\n*Deployment Remark*: ${custom_message}\n*Owners*: @meet190895,@rakebhag,@ashwani\\_devops,@vipin9458,@pdcdev,@Karishmasingh12,@surajairtel,@Rohit9011,@amit002\\_pec\n*Dashboard*: http://opsanalytics.airtel.com/d/DamkN2g4k/microservices-monitoring-aw?orgId=1%26viewPanel=33&parse_mode=Markdown'"
  } catch (err) {}
}

//def notifytelegram(msg,status,service,environment) {
//     sh "curl -s -X POST https://api.telegram.org/bot5322802961:AAEhK7qTGM8-_cfXJpeVioS_qwYQNASbgr8/sendMessage -H 'Content-Type: application/json' -d '{\"chat_id\": \"-1001164910468\", \"text\": \"${msg}\n\nStatus: ${status}\n\nService: ${service}\n\nEnvironment: ${environment}\"}' --proxy appproxy.airtel.com:4145"
//     sh "curl -s -X POST https://api.telegram.org/bot5322802961:AAEhK7qTGM8-_cfXJpeVioS_qwYQNASbgr8/sendMessage -H 'Content-Type: application/json' -d '{\"chat_id\": \"-687666839\", \"text\": \"${msg}\n\nStatus: ${status}\n\nService: ${service}\n\nEnvironment: ${environment}\"}' --proxy appproxy.airtel.com:4145"
//}

def notifytelegram_SIT(status,service,environment,Author,Commit,msg) {
      sh "curl -s -X POST https://api.telegram.org/bot5322802961:AAEhK7qTGM8-_cfXJpeVioS_qwYQNASbgr8/sendMessage -d 'chat_id=-1001164910468&text= *AlertName*: ${service} deployment\n*Environment*: ${environment}\n*Component*: ${service}\n*Commit*: ${Commit}\n*Author*: ${Author}\n*BUILD_URL*: ${env.BUILD_URL}\n*Status*: ${status}\n*Deployment_status*: ${msg}&parse_mode=Markdown'"
      // sh "curl -F chat_id="-1001164910468" -F document=@'${WORKSPACE}/serverlog.txt' -F caption="SIT build failed - server logs" https://api.telegram.org/bot5322802961:AAEhK7qTGM8-_cfXJpeVioS_qwYQNASbgr8/sendDocument --proxy appproxy.airtel.com:4145"
}

def DB_update(service, stage, version, status, annotation, build_number, deployment_state, lob, env) {
    try {
      deployment_datetime = sh(script: 'date +"%Y-%m-%dT%H:%M:%S:%z"', returnStdout: true).trim()
      deployment_date = sh(script: 'date +"%Y-%m-%d"', returnStdout: true).trim()
    //  previous_version = sh(script: "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_prod.config  -n airtelworks --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01 get deployment ${service} -o jsonpath='{.spec.template.spec.containers[].image}' | cut -d ':' -f2 | cut -d '-' -f1", returnStdout: true).trim()
      if (stage != '100%') {
        pod_names = sh(script: "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_prod.config  -n airtelworks --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01 get pods | grep ^${service}-canary | awk '{print \$1}' | tr '\n' ','", returnStdout: true).trim() 
      } else {
        pod_names = sh(script: "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_prod.config  -n airtelworks --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01 get pods | grep ^${service} | awk '{print \$1}' | tr '\n' ','", returnStdout: true).trim() 
      }
      sh """
          docker run -i core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/mongodb:4.2.4-debian-10-r0 mongo 10.5.31.144:27017/Deployment_data --username mongoadmin --password airtel123 --authenticationDatabase admin --eval 'db.Deployment_stats_${lob}_${env}.updateOne({\$and:[{"service" : \"${service}\"},{"build_number": \"${build_number}\"},{"deployment_stage": \"${stage}\"}]}, {\$set:{deployment_state:\"${deployment_state}\",status : \"${status}\",pod_names : \"${pod_names}\"}});'
      """
    } catch(err) {
      println("Error while updating in DB " + err)
    }
}

def DB_insert(service, stage, version, status, annotation, build_number, deployment_state, lob, env) {
    try {
      deployment_datetime = sh(script: 'date +"%Y-%m-%dT%H:%M:%S:%z"', returnStdout: true).trim()
      deployment_date = sh(script: 'date +"%Y-%m-%d"', returnStdout: true).trim()
      proceed_deployment = "false"
      if(stage != '100%') {
         previous_version = sh(script: "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_prod.config  -n airtelworks --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01 get deployment ${service} -o jsonpath='{.spec.template.spec.containers[].image}' | cut -d ':' -f2 | cut -d '-' -f1", returnStdout: true).trim()
        // pod_names = sh(script: "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_prod.config  -n airtelworks --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01 get pods | grep ^${service}-canary | awk '{print \$1}' | tr '\n' ','", returnStdout: true).trim()
      } else {
         previous_version = sh(script: """docker run -i core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/mongodb:4.2.4-debian-10-r0 mongo 10.5.31.144:27017/Deployment_data --username mongoadmin --password airtel123 --authenticationDatabase admin --eval 'db.Deployment_stats_${lob}_${env}.find({service: "${service}",deployment_stage: "60%",build_number: "${build_number}"},{"previous_version":-1,"_id":0}).sort({"deployment_time": -1}).limit(1).pretty()' | awk '{print \$4}' | tail -1 | sed 's/\"//g'""", returnStdout: true).trim()
        // pod_names = sh(script: "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_prod.config  -n airtelworks --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01 get pods | grep ^${service} | awk '{print \$1}' | tr '\n' ','", returnStdout: true).trim()
      }
      sh "docker run -i core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/mongodb:4.2.4-debian-10-r0 mongo 10.5.31.144:27017/Deployment_data --username mongoadmin --password airtel123 --authenticationDatabase admin --eval 'var document = { deployment_time : \"${deployment_datetime}\", deployment_date : \"${deployment_date}\", service : \"${service}\", deployment_stage : \"${stage}\", version : \"${version}\", annotation : \"${annotation}\", status : \"${status}\", build_number : \"${build_number}\", deployment_state : \"${deployment_state}\", previous_version : \"${previous_version}\", proceed_deployment : \"${proceed_deployment}\"}; db.Deployment_stats_${lob}_${env}.insert(document);'"
    } catch(err) {
      println("Error while inserting in DB " + err)
    }
}


def run_pmd() {
  try {
    if (lob == "AirtelWork" && project != "task-manager-service" && !isStaticUI) {
      echo "Running PMD..."
      sh "make analyse"
      echo "Publishing PMD Report.."
      publishHTML (target : [allowMissing: false,
          alwaysLinkToLastBuild: true,
          keepAll: true,
          reportDir: "${workspace}/target/site",
          reportFiles: 'pmd.html',
          reportName: "PMD Result Report",
          reportTitles: "PMD Result Report"]) 
      echo "Checking P1s...."
      int get_p1 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_1' | wc -l",returnStdout: true).trim()
      if (get_p1 == 0) {
          echo "There are no P1 issues in PMD results. Proceeding..."
      } else {
          int get_p2 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_2' | wc -l",returnStdout: true).trim()
          if (get_p2 == 0) {
              int get_p3 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_3' | wc -l",returnStdout: true).trim()
              if (get_p3 == 0) {
                  int get_p4 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_4' | wc -l",returnStdout: true).trim()
                  if (get_p4 == 0) {
                      int get_p5 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_5' | wc -l",returnStdout: true).trim()
                      if (get_p5 == 0) {
                          int p1_check = sh (script: "sed -n '/Priority_1/,/<h2><a name=\"Files\">/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                          echo "The P1 count is: ${p1_check}"
                          echo "There are P1 issues in PMD results. Please refer the report attached with this build and fix those!!"
                          sh "exit 1"
                      } else {
                          int p1_check = sh (script: "sed -n '/Priority_1/,/Priority_5/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                          echo "The P1 count is: ${p1_check}"
                          echo "There are P1 issues in PMD results. Please refer the report attached with this build and fix those!!"
                          sh "exit 1"
                      }
                  } else {
                      int p1_check = sh (script: "sed -n '/Priority_1/,/Priority_4/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                      echo "The P1 count is: ${p1_check}"
                      echo "There are P1 issues in PMD results. Please refer the report attached with this build and fix those!!"
                      sh "exit 1"
                  }
              } else {
                  int p1_check = sh (script: "sed -n '/Priority_1/,/Priority_3/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                  echo "The P1 count is: ${p1_check}"
                  echo "There are P1 issues in PMD results. Please refer the report attached with this build and fix those!!"
                  sh "exit 1"
              }
          } else {
              int p1_check = sh (script: "sed -n '/Priority_1/,/Priority_2/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
              echo "The P1 count is: ${p1_check}"
              echo "There are P1 issues in PMD results. Please refer the report attached with this build and fix those!!"
              sh "exit 1"
          }
        }
        echo "Checking P2s...."
        int get_p2 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_2' | wc -l",returnStdout: true).trim()
        if (get_p2 == 0) {
            echo "There are no P2 issues in PMD results. Proceeding..."
        } else {
            int get_p3 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_3' | wc -l",returnStdout: true).trim()
            if (get_p3 == 0) {
                int get_p4 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_4' | wc -l",returnStdout: true).trim()
                if (get_p4 == 0) {
                    int get_p5 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_5' | wc -l",returnStdout: true).trim()
                    if (get_p5 == 0) {
                        int p2_check = sh (script: "sed -n '/Priority_2/,/<h2><a name=\"Files\">/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                        echo "The P2 count is: ${p2_check}"
                        echo "There are P2 issues in PMD results. Please refer the report attached with this build and fix those!!"
                        sh "exit 1"
                    } else {
                        int p2_check = sh (script: "sed -n '/Priority_2/,/Priority_5/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                        echo "The P2 count is: ${p2_check}"
                        echo "There are P2 issues in PMD results. Please refer the report attached with this build and fix those!!"
                        sh "exit 1"
                    }
                } else {
                    int p2_check = sh (script: "sed -n '/Priority_2/,/Priority_4/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                    echo "The P2 count is: ${p2_check}"
                    echo "There are P2 issues in PMD results. Please refer the report attached with this build and fix those!!"
                    sh "exit 1"
                }
            } else {
                int p2_check = sh (script: "sed -n '/Priority_2/,/Priority_3/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                echo "The P2 count is: ${p2_check}"
                echo "There are P2 issues in PMD results. Please refer the report attached with this build and fix those!!"
                sh "exit 1"
            }
        }
        echo "Checking P3s...."
        int get_p3 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_3' | wc -l",returnStdout: true).trim()
        if (get_p3 == 0) {
            echo "There are no P3 issues in PMD results. Proceeding..."
        } else {
            int get_p4 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_4' | wc -l",returnStdout: true).trim()
            if (get_p4 == 0) {
                int get_p5 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_5' | wc -l",returnStdout: true).trim()
                if (get_p5 == 0) {
                    int p3_check = sh (script: "sed -n '/Priority_3/,/<h2><a name=\"Files\">/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                    echo "The P3 count is: ${p3_check}"
                    if (p3_check < 25) {
                        echo "The P3 conflicts are less than 25 which is OK for now. Proceeding..."
                    } else {
                        echo "The P3 conflicts are more than 25.Please refer the report attached with this build and fix those!!"
                        sh "exit 1"
                    }
                } else {
                    int p3_check = sh (script: "sed -n '/Priority_3/,/Priority_5/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                    echo "The P3 count is: ${p3_check}"
                    if (p3_check < 25) {
                        echo "The P3 conflicts are less than 25 which is OK for now. Proceeding..."
                    } else {
                        echo "The P3 conflicts are more than 25.Please refer the report attached with this build and fix those!!"
                        sh "exit 1"
                    }
                }
            } else {
                int p3_check = sh (script: "sed -n '/Priority_3/,/Priority_4/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                echo "The P3 count is: ${p3_check}"
                if (p3_check < 25) {
                        echo "The P3 conflicts are less than 25 which is OK for now. Proceeding..."
                } else {
                    echo "The P3 conflicts are more than 25.Please refer the report attached with this build and fix those!!"
                    sh "exit 1"
                }
            }
        }
        echo "Checking P4s...."
        int get_p4 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_4' | wc -l",returnStdout: true).trim()
        if (get_p4 < 10) {
            echo "There are no P4 issues in PMD results. Proceeding..."
        } else {
            int get_p5 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_5' | wc -l",returnStdout: true).trim()
            if (get_p5 == 0) {
                int p4_check = sh (script: "sed -n '/Priority_4/,/<h2><a name=\"Files\">/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                echo "The P4 count is: ${p4_check}"
                echo "There are P4 issues in PMD results. Please refer the report attached with this build and fix those!!"
                sh "exit 1"
            } else {
                int p4_check = sh (script: "sed -n '/Priority_4/,/Priority_5/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
                echo "The P4 count is: ${p4_check}"
                echo "There are P4 issues in PMD results. Please refer the report attached with this build and fix those!!"
                sh "exit 1"
            }
        }
        echo "Checking P5s...."
        int get_p5 = sh (script: "cat ${workspace}/target/site/pmd.html | grep 'Priority_5' | wc -l",returnStdout: true).trim()
        if (get_p5 == 0) {
            echo "There are no P5 issues in PMD results. Proceeding..."
        } else {
            int p5_check = sh (script: "sed -n '/Priority_5/,/<h2><a name=\"Files\">/p' ${workspace}/target/site/pmd.html | grep 'externalLink' | wc -l",returnStdout: true).trim()
            echo "The P5 count is: ${p5_check}"
            echo "There are P5 issues in PMD results. Please refer the report attached with this build and fix those!!"
            sh "exit 1"
        }
    }
  }
  catch (err) {
      msg = ""
      throw err
  }
}


// ------------------------------------------------------------------------------------------------------------------------
// [START] Slack notification
// ------------------------------------------------------------------------------------------------------------------------
def notify(msg,status,service,environment,webhook) {
   echo "[notify] Teams ++++++++++++++++++++++++++++++++++"
   office365ConnectorSend webhookUrl: "${webhook}",

            message: "Airtel_X_Labs (ProductEngineering)",
            factDefinitions: [[name: "Message", template: msg],
                                  [name: "Service", template: service.toUpperCase()],
                                  [name: "Environment", template: environment.toUpperCase()],
                                  [name: "Build #", template: env.BUILD_NUMBER],
                                  [name: "URL", template: "[ViewBuild#${env.BUILD_NUMBER}](${env.BUILD_URL})" ],
                                  [name: "Build Logs", template: "[BuildLogs#${env.BUILD_NUMBER}](${env.BUILD_URL}console)" ]],
            status: "${status}"
    }
def reportMail(platforms_map,service,url){
  sh("sed -i 's#__service__#${service.toLowerCase()}#' ./sandbox/config/SonarReport.html")
  sh("sed -i 's#__SONARURL__#${url}#' ./sandbox/config/SonarReport.html")
  from_mail = "SRE Digital Acquisition Team <SREDigitalAcquisitionTeam@airtel.com>"
  to_mail = "${platforms_map.mail.to}"
  mailBody = readFile('sandbox/config/SonarReport.html')
  mailSubject = "Code Quality Report | ${platforms_map.name.toUpperCase()} | ${service.toUpperCase()}"
  emailext mimeType: 'text/html', body: mailBody, from: from_mail, replyTo: from_mail, subject: mailSubject, to: to_mail
}

def sendMail(platforms_map,service,version,status,jiraid){
      sh("sed -i 's#__STATUS__#${status}#' ./sandbox/config/DeploymentReport.html")
      sh("sed -i 's#__service__#${service.toLowerCase()}#' ./sandbox/config/DeploymentReport.html")
      sh("sed -i 's#__JiraID__#${jiraid}#' ./sandbox/config/DeploymentReport.html")
      mailBody = readFile('sandbox/config/DeploymentReport.html')
      mailSubject = "Deployment Completed | ${platforms_map.name.toUpperCase()} | ${service.toUpperCase()}:${version}"
      from_mail = "SRE Digital Acquisition Team <SREDigitalAcquisitionTeam@airtel.com>"
      cc_mail = "${platforms_map.mail.cc}"
      to_mail = "${platforms_map.mail.to}"
      emailext mimeType: 'text/html', body: mailBody, from: from_mail, replyTo: from_mail, subject: mailSubject, to: to_mail
}
// ------------------------------------------------------------------------------------------------------------------------
// [END] Slack notification
// ------------------------------------------------------------------------------------------------------------------------

// ------------------------------------------------------------------------------------------------------------------------
// [START] Templating for Kubernetes
// ------------------------------------------------------------------------------------------------------------------------
def scaleReplicas(app_resources,platform,service,rollout) {
  try {
  config_file = "kubeconfig/${platform}/gcp_prod.config"
  kube_namespace = platforms_map.environments.production.namespace
  maxReplicas = app_resources.production.maxReplicas
  minReplicas = app_resources.production.minReplicas
  def replicas = minReplicas/5
  echo "${replicas}"
  replicas = ((float)replicas).round(0)
  replicas = (replicas).toInteger()
  if (replicas == 0) {
      replicas = 1
  }
  echo "${replicas}"
  //def int maxReplicas = (maxReplicas - replicas) as Integer
  echo "${maxReplicas}"
  if (rollout == 40) {
    replicas = (2*replicas)
    maxReplicas = (maxReplicas - replicas).toInteger()
  }
  else if (rollout == 60) {
    replicas = (3*replicas)
    maxReplicas = (maxReplicas - replicas).toInteger()
  }
  //replicas = replicas as Integer
  else if (rollout == 100 && service == "task-manager-service") {
    replicas = 2
  }
  else { replicas = 1 }
  if (rollout == 100) {
      sh "kubectl --kubeconfig ${WORKSPACE}/sandbox/${config_file} -n ${kube_namespace} --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01  patch hpa ${service} --patch '{\"spec\":{\"maxReplicas\":${maxReplicas}}}'"
  }
      sh "kubectl --kubeconfig ${WORKSPACE}/sandbox/${config_file} -n ${kube_namespace} --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01  patch hpa ${service} --patch '{\"spec\":{\"minReplicas\":${maxReplicas}}}'"
      sh "kubectl --kubeconfig ${WORKSPACE}/sandbox/${config_file} -n ${kube_namespace} --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01  patch hpa ${service} --patch '{\"spec\":{\"maxReplicas\":${maxReplicas}}}'"
      sleep 10
      sh "kubectl --kubeconfig ${WORKSPACE}/sandbox/${config_file} -n ${kube_namespace} --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01  scale --replicas=${replicas} deployment ${service}-canary"
      sh "kubectl --kubeconfig ${WORKSPACE}/sandbox/${config_file} -n ${kube_namespace} --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01  rollout status deployment ${service}-canary --timeout=4200s"
  } catch(err) {
    throw err
  }
}

def scaleReplicasphase1(app_resources,platform,service,rollout) {
  try {
  config_file = "kubeconfig/${platform}/prod.config"
  kube_namespace = platforms_map.environments.production.namespace
  maxReplicas = app_resources.productionphase1.maxReplicas
  minReplicas = app_resources.productionphase1.minReplicas
  kube_context = "airtelworksphase1"
  def replicas = minReplicas/5
  echo "${replicas}"
  replicas = ((float)replicas).round(0)
  replicas = (replicas).toInteger()
  if (replicas == 0) {
      replicas = 1
  }
  echo "${replicas}"
  //def int maxReplicas = (maxReplicas - replicas) as Integer
  echo "${maxReplicas}"
  if (rollout == 40) {
    replicas = (2*replicas)
    maxReplicas = (maxReplicas - replicas).toInteger()
  }
  else if (rollout == 60) {
    replicas = (3*replicas)
    maxReplicas = (maxReplicas - replicas).toInteger()
  }
  //replicas = replicas as Integer
  else if (rollout == 100 && service == "task-manager-service") {
    replicas = 2
  }
  else { replicas = 1 }
    if (rollout == 100) {
      sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/kubectl:latest -n ${kube_namespace} --context=${kube_context} patch hpa ${service} --patch '{\"spec\":{\"maxReplicas\":${maxReplicas}}}'"
    }
      sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/kubectl:latest -n ${kube_namespace} --context=${kube_context} patch hpa ${service} --patch '{\"spec\":{\"minReplicas\":${maxReplicas}}}'"
      sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/kubectl:latest -n ${kube_namespace} --context=${kube_context} patch hpa ${service} --patch '{\"spec\":{\"maxReplicas\":${maxReplicas}}}'"
      sleep 10
      sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/kubectl:latest -n ${kube_namespace} --context=${kube_context} scale --replicas=${replicas} deployment ${service}-canary"
      sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/kubectl:latest -n ${kube_namespace} --context=${kube_context} rollout status deployment ${service}-canary --timeout=4200s"
  } catch(err) {
    throw err
  }
}

def generateTemplate(env,app_filler,app_resources,platforms_map,git_proj_key,imageTag){
  if (["canary"].contains(env)) {
      env="production"
      is_canary = true
  } else { is_canary = false }
  def metricsPathString = "/metrics";
  def metricsEnabled = false;
  def healthCheckPathString = "/health";
  def environmentText = JsonOutput.toJson(app_filler.environment)
  resourcesText = JsonOutput.toJson(app_resources."${env}".resources)
  replicas = app_resources."${env}".replicas
  maxReplicas = app_resources."${env}".maxReplicas
  minReplicas = app_resources."${env}".minReplicas
  serviceSubDomain = "${project}"
  def month=new Date().format("MMM").substring(0,3).toLowerCase()
  def year=new Date().format("YYYY")
  def shared_images_date = "sharedimages_" + month + "_" + year

  sh("mkdir -p k8s_${env}")
  if ((env == "sit" || env == "sit3" || env == "production") && lob == "AirtelWork") {
    sh("cp -r sandbox/gcp_templates/* k8s_${env}/")
    registry_url = "asia-south2-docker.pkg.dev/prj-cloudops-devops-services"
  }
  else {
    sh("cp -r sandbox/kube_templates/* k8s_${env}/")
    registry_url = platforms_map.registry.url
  }

  if (environmentText == "[]") {
    environmentText = ""
  }
  else {
    environmentText = ", ${environmentText[1..-2]}"
  }

  if (app_filler.healthCheckPath != null) {
    healthCheckPathString = app_filler.healthCheckPath;
  }

  if (app_filler.metricsPath != null) {
    metricsPathString = app_filler.metricsPath;
    metricsEnabled = true;
  }
  try{
      serviceSubDomain = app_filler.serviceSubDomain;

  }
  catch (err){
      serviceSubDomain = "${project}"
  }
  if (serviceSubDomain == null) {
    serviceSubDomain = "${project}"
  }
   try{
      if (app_resources."${env}".hostAliases){
        hostAliases = JsonOutput.toJson(app_resources."${env}".hostAliases)
      }
      else{
        hostAliases = "[]"
      }

  }
  catch (err){
      hostAliases = "[]"
  }
  if (hostAliases == null ) {
      hostAliases = "[]"
  }
  try{
      if (app_resources."${env}".volumes.names){
        volumes = JsonOutput.toJson(app_resources."${env}".volumes.names)
      }
      else{
        volumes = "[]"
      }

      if (app_resources."${env}".volumes.mounts){
        volumeMounts = JsonOutput.toJson(app_resources."${env}".volumes.mounts)
      }
      else{
        volumeMounts = "[]"
      }
  }
  catch (err){
      volumeMounts = "[]"
      volumes = "[]"
  }
  if (volumeMounts == null || volumes == null ) {
    volumeMounts = "[]"
      volumes = "[]"
  }



  deployment_base_domain = platforms_map.environments."${env}".domain
  namespace_kube = platforms_map.environments."${env}".namespace

  registry_creds = platforms_map.registry.credentials

    if (is_performance) {
      sh("sed -i 's#__serviceSubDomain__#__serviceName__#'     ./k8s_${env}/*")
    } else {
      sh("sed -i 's#__serviceSubDomain__#${serviceSubDomain}#'     ./k8s_${env}/*")
    }
    sh("sed -i 's#__baseDomain__#${deployment_base_domain}#'     ./k8s_${env}/*")
    sh("sed -i 's#__platform__#${git_proj_key.toLowerCase()}#'   ./k8s_${env}/*")
    sh("sed -i 's#__imageName__#${imageTag}#'                    ./k8s_${env}/*")
    if (is_performance) {
      sh("sed -i 's#__serviceName__#${project}-performance#'                   ./k8s_${env}/*")
    } else {
      sh("sed -i 's#__serviceName__#${project}#'                   ./k8s_${env}/*")
    }
    sh("sed -i 's#__tier__#${app_filler.tier}#'                  ./k8s_${env}/*")
    sh("sed -i 's#__owner__#${app_filler.owner}#'                ./k8s_${env}/*")
    sh("sed -i 's#__version__#${app_filler.version}#'            ./k8s_${env}/*")
    sh("sed -i 's#__environment__#${environmentText}#'           ./k8s_${env}/*")
    sh("sed -i 's#__slackUser__#${app_filler.slackUser}#'        ./k8s_${env}/*")
    sh("sed -i 's#__port__#${app_filler.port}#'                  ./k8s_${env}/*")
    sh("sed -i 's#__labels__#${app_filler.labels}#'              ./k8s_${env}/*")
    sh("sed -i 's#__namespace__#${namespace_kube}#'              ./k8s_${env}/*")
    if (is_canary) {
      if (project == "task-manager-service") {
        sh("sed -i 's#__replicas__#2#'                     ./k8s_${env}/*")
           }
           else {
        sh("sed -i 's#__replicas__#1#'                     ./k8s_${env}/*")
           }
    }
    else {
    sh("sed -i 's#__replicas__#${replicas}#'                     ./k8s_${env}/*")
    }
    if (project == "download-service") {
      sh("sed -i 's#__proxytimeout__#700s#'                     ./k8s_${env}/*")
    } else {
      sh("sed -i 's#__proxytimeout__#60s#'                     ./k8s_${env}/*")
    }
    sh("sed -i 's#__maxReplicas__#${maxReplicas}#'               ./k8s_${env}/*")
    sh("sed -i 's#__minReplicas__#${minReplicas}#'             ./k8s_${env}/*")
    sh("sed -i 's#__dockerRegistry__#${registry_url}#'           ./k8s_${env}/*")
    sh("sed -i 's#__registryCreds__#${registry_creds}#'          ./k8s_${env}/*")
    sh("sed -i 's#__volumes__#${volumes}#'                     ./k8s_${env}/*")
    sh("sed -i 's#__hostAliases__#${hostAliases}#'             ./k8s_${env}/*")
    sh("sed -i 's#__volumeMounts__#${volumeMounts}#'             ./k8s_${env}/*")
    sh("sed -i 's#__resources__#${resourcesText}#'             ./k8s_${env}/*")
    sh("sed -i 's#__healthCheckPath__#${healthCheckPathString}#' ./k8s_${env}/*")
    sh("sed -i 's#__metricsPath__#${metricsPathString}#'         ./k8s_${env}/*")
    sh("sed -i 's#__metricsEnabled__#${metricsEnabled}#'         ./k8s_${env}/*")
    sh("sed -i 's#__sharedimagesDate__#${shared_images_date}#' ./k8s_${env}/*")
  
    if (is_canary) {
      if ((env == "sit" || env == "sit3" || env == "production") && lob == "AirtelWork") {
        sh("sed -i '89s#${project}#${project}-canary#g'     ./k8s_${env}/deployment.json")
      } else {
          sh("sed -i '92s#${project}#${project}-canary#g'     ./k8s_${env}/deployment.json")
      }
    }
    if (is_performance) {
    sh("sed -i '25s#${git_proj_key.toLowerCase()}-${project}-performance#${git_proj_key.toLowerCase()}-${project}#g'     ./k8s_${env}/deployment.json")
  }
}

def generateTemplatephase1(env,app_filler,app_resources,platforms_map,git_proj_key,imageTag){
  if (["canary"].contains(env)) {
      env="productionphase1"
      is_canary = true
  } else { is_canary = false }
  def metricsPathString = "/metrics";
  def metricsEnabled = false;
  def healthCheckPathString = "/health";
  def environmentText = JsonOutput.toJson(app_filler.environment)
  resourcesText = JsonOutput.toJson(app_resources."${env}".resources)
  replicas = app_resources."${env}".replicas
  maxReplicas = app_resources."${env}".maxReplicas
  minReplicas = app_resources."${env}".minReplicas
  serviceSubDomain = "${project}"

  sh("mkdir -p k8s_${env}")
  sh("cp -r sandbox/kube_templates/* k8s_${env}/")

  if (environmentText == "[]") {
    environmentText = ""
  }
  else {
    environmentText = ", ${environmentText[1..-2]}"
  }

  if (app_filler.healthCheckPath != null) {
    healthCheckPathString = app_filler.healthCheckPath;
  }

  if (app_filler.metricsPath != null) {
    metricsPathString = app_filler.metricsPath;
    metricsEnabled = true;
  }
  try{
      serviceSubDomain = app_filler.serviceSubDomain;

  }
  catch (err){
      serviceSubDomain = "${project}"
  }
  if (serviceSubDomain == null) {
    serviceSubDomain = "${project}"
  }
   try{
      if (app_resources."${env}".hostAliases){
        hostAliases = JsonOutput.toJson(app_resources."${env}".hostAliases)
      }
      else{
        hostAliases = "[]"
      }

  }
  catch (err){
      hostAliases = "[]"
  }
  if (hostAliases == null ) {
      hostAliases = "[]"
  }
  try{
      if (app_resources."${env}".volumes.names){
        volumes = JsonOutput.toJson(app_resources."${env}".volumes.names)
      }
      else{
        volumes = "[]"
      }

      if (app_resources."${env}".volumes.mounts){
        volumeMounts = JsonOutput.toJson(app_resources."${env}".volumes.mounts)
      }
      else{
        volumeMounts = "[]"
      }
  }
  catch (err){
      volumeMounts = "[]"
      volumes = "[]"
  }
  if (volumeMounts == null || volumes == null ) {
    volumeMounts = "[]"
      volumes = "[]"
  }



  deployment_base_domain = platforms_map.environments.production.domain
  namespace_kube = platforms_map.environments.production.namespace

  registry_url = platforms_map.registry.url
  registry_creds = platforms_map.registry.credentials

    if (is_performance) {
      sh("sed -i 's#__serviceSubDomain__#__serviceName__#'     ./k8s_${env}/*")
    } else {
      sh("sed -i 's#__serviceSubDomain__#${serviceSubDomain}#'     ./k8s_${env}/*")
    }
    sh("sed -i 's#__baseDomain__#${deployment_base_domain}#'     ./k8s_${env}/*")
    sh("sed -i 's#__platform__#${git_proj_key.toLowerCase()}#'   ./k8s_${env}/*")
    sh("sed -i 's#__imageName__#${imageTag}#'                    ./k8s_${env}/*")
    if (is_performance) {
      sh("sed -i 's#__serviceName__#${project}-performance#'                   ./k8s_${env}/*")
    } else {
      sh("sed -i 's#__serviceName__#${project}#'                   ./k8s_${env}/*")
    }
    sh("sed -i 's#__tier__#${app_filler.tier}#'                  ./k8s_${env}/*")
    sh("sed -i 's#__owner__#${app_filler.owner}#'                ./k8s_${env}/*")
    sh("sed -i 's#__version__#${app_filler.version}#'            ./k8s_${env}/*")
    sh("sed -i 's#__environment__#${environmentText}#'           ./k8s_${env}/*")
    sh("sed -i 's#__slackUser__#${app_filler.slackUser}#'        ./k8s_${env}/*")
    sh("sed -i 's#__port__#${app_filler.port}#'                  ./k8s_${env}/*")
    sh("sed -i 's#__labels__#${app_filler.labels}#'              ./k8s_${env}/*")
    sh("sed -i 's#__namespace__#${namespace_kube}#'              ./k8s_${env}/*")
    if (is_canary) {
      if (project == "task-manager-service") {
        sh("sed -i 's#__replicas__#0#'                     ./k8s_${env}/*")
           }
           else {
        sh("sed -i 's#__replicas__#0#'                     ./k8s_${env}/*")
           }
    }
    else {
    sh("sed -i 's#__replicas__#${replicas}#'                     ./k8s_${env}/*")
    }
    if (project == "download-service") {
      sh("sed -i 's#__proxytimeout__#700s#'                     ./k8s_${env}/*")
    } else {
      sh("sed -i 's#__proxytimeout__#60s#'                     ./k8s_${env}/*")
    }
    sh("sed -i 's#__maxReplicas__#${maxReplicas}#'               ./k8s_${env}/*")
    sh("sed -i 's#__minReplicas__#${minReplicas}#'             ./k8s_${env}/*")
    sh("sed -i 's#__dockerRegistry__#${registry_url}#'           ./k8s_${env}/*")
    sh("sed -i 's#__registryCreds__#${registry_creds}#'          ./k8s_${env}/*")
    sh("sed -i 's#__volumes__#${volumes}#'                     ./k8s_${env}/*")
    sh("sed -i 's#__hostAliases__#${hostAliases}#'             ./k8s_${env}/*")
    sh("sed -i 's#__volumeMounts__#${volumeMounts}#'             ./k8s_${env}/*")
    sh("sed -i 's#__resources__#${resourcesText}#'             ./k8s_${env}/*")
    sh("sed -i 's#__healthCheckPath__#${healthCheckPathString}#' ./k8s_${env}/*")
    sh("sed -i 's#__metricsPath__#${metricsPathString}#'         ./k8s_${env}/*")
    sh("sed -i 's#__metricsEnabled__#${metricsEnabled}#'         ./k8s_${env}/*")
    if (is_canary) {
      sh("sed -i '92s#${project}#${project}-canary#g'     ./k8s_${env}/deployment.json")
    }
    if (is_performance) {
    sh("sed -i '25s#${git_proj_key.toLowerCase()}-${project}-performance#${git_proj_key.toLowerCase()}-${project}#g'     ./k8s_${env}/deployment.json")
  }
}
// ------------------------------------------------------------------------------------------------------------------------
// [STOP] Templating for Kubernetes
// ------------------------------------------------------------------------------------------------------------------------

// ------------------------------------------------------------------------------------------------------------------------
// [START] Deployment Generic function
// ------------------------------------------------------------------------------------------------------------------------
def deploy(environment,platform, nextEnvironment, messageParams, slackNotificationChannel, user, text = "") {
  generateTemplate(environment,app_filler,app_resources,platforms_map,git_proj_key,imageTag)
  try {
     if (["canary"].contains(environment)) {
      environment="production"
      is_canary = true
    }
    else { is_canary = false }
    kube_namespace = platforms_map.environments."${environment}".namespace
    kubectl_bin = "core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/kubectl:latest"
    if (["beta", "production"].contains(environment)) {
      config_file = "kubeconfig/${platform}/prod.config"
    }
    else{
      config_file = "kubeconfig/${platform}/dev.config"
    }
    sh "sudo chown -R jenkins:root ${workspace}*"
    if (environment == "sit" && lob == "AirtelWork") {
      sh "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_dev.config apply -f ./k8s_${environment} --context=gke_prj-acqs-nprd-work-svc-01_asia-south2_gke-aw-nprd-ndmz-sit-01 -n airtelworksit"
    } else if (environment == "sit3" && lob == "AirtelWork") {
        sh "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_dev.config apply -f ./k8s_${environment} --context=gke_prj-acqs-nprd-work-svc-01_asia-south2_gke-aw-nprd-ndmz-at-01 -n airtelworkat"
    } else if (environment == "production" && lob == "AirtelWork") {
        sh "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_prod.config apply -f ./k8s_${environment} --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01 -n airtelworks"
        if (is_canary) {
           try{sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_${environment}:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_namespace} set image deployment/${project}-canary ${project}=${REGISTRY_URL}/${imageTag}"} catch (err) {}
           try{sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_${environment}:/k8s ${kubectl_bin} -n ${kube_namespace} --context=airtelworksphase1 set image deployment/${project}-canary ${project}=${REGISTRY_URL}/${imageTag}"} catch (err) {}
        } else {
           try{sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_${environment}:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_namespace} set image deployment/${project} ${project}=${REGISTRY_URL}/${imageTag}"} catch (err) {}
        }
    }
    else {
    try{sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_${environment}:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_namespace} apply -f /k8s"} catch (err) {}
    }
  //}
  // catch (err) {
  //   throw err
  // }
    sleep 60
    if (["development", "sit", "sit3", "sit2", "production"].contains(environment)) {
      if (is_canary) {
      project_key = "${project}-canary"
      } else {
      project_key = "${project}"
      }
      if (environment == "sit" && lob == "AirtelWork") {
      sh "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_dev.config rollout status deployment ${project_key} --context=gke_prj-acqs-nprd-work-svc-01_asia-south2_gke-aw-nprd-ndmz-sit-01 -n airtelworksit --timeout=4200s > ${WORKSPACE}/out1"
      } else if (environment == "sit3" && lob == "AirtelWork") {
      sh "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_dev.config rollout status deployment ${project_key} --context=gke_prj-acqs-nprd-work-svc-01_asia-south2_gke-aw-nprd-ndmz-at-01 -n airtelworkat --timeout=4200s > ${WORKSPACE}/out1"
      } else if (environment == "production" && lob == "AirtelWork") {
        sh "kubectl --kubeconfig ${WORKSPACE}/sandbox/kubeconfig/airw/gcp_prod.config rollout status deployment ${project_key} --context=gke_prj-acqs-prd-work-svc-01_asia-south2_gke-aw-prd-ndmz-prod-01 -n airtelworks --timeout=4200s > ${WORKSPACE}/out1"
      }     
      else {
      try{sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_${environment}:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_namespace} rollout status deployment ${project_key} --timeout=4200s > ${WORKSPACE}/out1"} catch (err) {}
      }
    }
  }
  catch (err) {
    throw err
  }
  finally
  {
      def Deploy_Status = sh (returnStdout: true, script: "cat ${WORKSPACE}/out1 | grep -E 'successfully' > /dev/null && echo 'true' || echo 'false'").trim()
      echo " Status is ${Deploy_Status} "
      if (Deploy_Status != "true" && environment != "beta")
      {
         podname = sh(returnStdout: true, script: "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_${environment}:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_namespace} get pods --sort-by=.metadata.creationTimestamp | grep ${project} | tail -1 | awk '{print \$1}'")

         echo "podname is ${podname}"
         echo "++++++++++++++++++++++++++++++++++ Console Logs ++++++++++++++++++++++++++++++++++++++"
         sh("docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_${environment}:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_namespace} logs --tail=200 ${podname}")
         //echo "++++++++++++++++++++++++++++++++++ Server Logs +++++++++++++++++++++++++++++++++++++++"
         //sh("docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_${environment}:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_namespace} exec -i ${podname} -- tail -100 -f /app/logs/${podname}/server.log")

         error("Container Deployment Failed... To view the error, check the logs above")
         currentBuild.result = 'ABORTED'
         echo ":::::::::::::Container Deployment Failed... To view the error, check the logs above:::::::::::::"
         throw new RuntimeException("Container Deployment Failed... To view the error, check the logs above")
      }
      else {
          echo "Container Deployment is successful"
      }
  }
}

def deployphase1(environment,platform, nextEnvironment, messageParams, slackNotificationChannel, user, text = "") {
  generateTemplatephase1(environment,app_filler,app_resources,platforms_map,git_proj_key,imageTag)
  try {
     if (["canary"].contains(environment)) {
      environment="production"
      is_canary = true
    }
    else { is_canary = false }
    kube_namespace = platforms_map.environments."${environment}".namespace
    kubectl_bin = "core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/kubectl:latest"
    kube_context = "airtelworksphase1"
    if (["beta", "production"].contains(environment)) {
      config_file = "kubeconfig/${platform}/prod.config"
    }
    else{
      config_file = "kubeconfig/${platform}/dev.config"
    }
    sh "sudo chown -R jenkins:root ${workspace}*"
    try{sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_productionphase1:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_context} apply -f /k8s"} catch (err) {}
  }
  catch (err) {
    throw err
  }
  sleep 60
  if (["development", "sit", "sit3", "sit2", "production"].contains(environment)) {
  if (is_canary) {
    project_key = "${project}-canary"
  } else {
    project_key = "${project}"
  }
  try{sh "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_k8s_productionphase1:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_context} rollout status deployment ${project_key} --timeout=4200s > ${WORKSPACE}/out1"} catch (err) {}
  catch (err) {
    throw err
  }
  finally
  {
      def Deploy_Status = sh (returnStdout: true, script: "cat ${WORKSPACE}/out1 | grep -E 'successfully' > /dev/null && echo 'true' || echo 'false'").trim()
      echo " Status is ${Deploy_Status} "
      if (Deploy_Status != "true")
      {
         podname = sh(returnStdout: true, script: "docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_productionphase1:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_context} get pods --sort-by=.metadata.creationTimestamp | grep ${project} | tail -1 | awk '{print \$1}'")

         echo "podname is ${podname}"
         echo "++++++++++++++++++++++++++++++++++ Console Logs ++++++++++++++++++++++++++++++++++++++"
         sh("docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_productionphase1:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_context} logs --tail=200 ${podname}")
         //echo "++++++++++++++++++++++++++++++++++ Server Logs +++++++++++++++++++++++++++++++++++++++"
         //sh("docker run -i --rm -v ${WORKSPACE}/sandbox/${config_file}:/.kube/config -v ${WORKSPACE}/k8s_productionphase1:/k8s ${kubectl_bin} -n ${kube_namespace} --context=${kube_context} exec -i ${podname} -- tail -100 -f /app/logs/${podname}/server.log")

         error("Container Deployment Failed... To view the error, check the logs above")
      }
      else {
          echo "Container Deployment is successful"
      }
  }
 }
}
// ------------------------------------------------------------------------------------------------------------------------
// [END] Deployment Generic function
// ------------------------------------------------------------------------------------------------------------------------

// ------------------------------------------------------------------------------------------------------------------------
// [START] Ansible deploy
// ------------------------------------------------------------------------------------------------------------------------
def ansible_deploy(inventory,playbook,target,extras){
  try{
    playbook = "ansible/playbooks/${playbook}"
    inventory = "ansible/inventory/${inventory}"
    ssh_key = "ansible/inventory/files/ansible.private.ssh"
    sh("chmod 600 ansible/inventory/files/ansible.private.ssh")    
    if (lob == "AirtelWork") {
      ansible_cmd="sudo /root/.local/bin/ansible-playbook ${playbook} -i ${inventory} -u ansible --private-key=${ssh_key} "
    }
    else {
      ansible_cmd="ansible-playbook ${playbook} -i ${inventory} -u ansible --private-key=${ssh_key} "
    }
    sh("${ansible_cmd} -e 'target=${target}' ${extras} -b")
  }
  catch (err){
    throw err
    }
}

def monitoring_kpi(env) {
  try {
    print("calculating KPI's")
    sh "cp -rvf sandbox/* ."
    app = docker.build("kpi");
    result = app.inside {
    sh("python services/monitoring_kpi.py -p ${git_proj_key.toLowerCase()} -e ${env} -s ${project}")
    }
  }
  catch (err) {
    throw err
  }
}

def checkoutcode() {
    try {
        git_proj_key= "${scm.getUserRemoteConfigs()[0].getUrl().tokenize('/')[-2]}"
        git_proj_name= "${scm.getUserRemoteConfigs()[0].getUrl().tokenize('/')[-1]}"
        checkout([
            $class: 'GitSCM',
            branches: scm.branches,
            extensions: scm.extensions + [[$class: 'CleanCheckout']],
            userRemoteConfigs: [[credentialsId: 'rakesh_bhagat', url: "ssh://git@code.airtelworld.in:7999/${git_proj_key}/${git_proj_name}"]]
        ])
        checkout([
            $class: 'GitSCM',
            branches: [[name: 'refs/heads/master']],
            doGenerateSubmoduleConfigurations: false,
            extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'sandbox']],
            submoduleCfg: [],
            userRemoteConfigs: [[credentialsId: 'rakesh_bhagat', url: 'ssh://git@code.airtelworld.in:7999/infra/sandbox.git']]
        ])
        checkout([
           $class: 'GitSCM',
           branches: [[name: 'refs/heads/master']],
           doGenerateSubmoduleConfigurations: false,
           extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'infrastructure']],
           submoduleCfg: [],
           userRemoteConfigs: [[credentialsId: 'rakesh_bhagat', url: "ssh://git@code.airtelworld.in:7999/${git_proj_key}/infrastructure"]]
        ])
        checkout([
           $class: 'GitSCM',
           branches: [[name: 'refs/heads/master']],
           doGenerateSubmoduleConfigurations: false,
           extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'ansible']],
           submoduleCfg: [],
           userRemoteConfigs: [[credentialsId: 'rakesh_bhagat', url: 'ssh://git@code.airtelworld.in:7999/infra/ansible.git']]
        ])
        populateGlobalVariables()
    }  catch (err) {
            throw err
    }
}

def coverity() {
    echo "Running coverity"
    variables = [:]
    variables.put('extractedPath','/data/jenkins') 
    variables.put('blackduckprojectname', "${lob}_${project}")
    variables.put('blackduckversionname', "${env.BRANCH_NAME}")
    variables.put('COV_STREAM', "${lob}_${project}_${env.BRANCH_NAME}")
    variables.put('COV_PROJECT', "${lob}_${project}")
    variables.put('blackduckMavenPath', '/opt/maven/bin/mvn')
    variables.put('balckduckMavencommand', 'clean install --settings=sandbox/config/maven_creds.xml -DskipTests')
    security(variables)
}

def security_check() {
  withCredentials([string(credentialsId: 'jenkins-token', variable: 'TOKEN')]) {
    sh "curl -X POST 'http://jenkins-service.airtelworld.in:8080/job/AGILE/job/AirtelWork/job/security_scan/buildWithParameters?Repo=${project}&Jira=DIGI-587&Branch=${env.BRANCH_NAME}&Appname=${git_proj_key.toLowerCase()}' --user '${TOKEN}'"
    sleep 30
    build_id = jsonParse(sh(returnStdout: true, script: "curl  http://jenkins-service.airtelworld.in:8080/job/AGILE/job/AirtelWork/job/security_scan/lastBuild/api/json --user '${TOKEN}'")).id
    status_json = jsonParse(sh(returnStdout: true, script: "curl  http://jenkins-service.airtelworld.in:8080/job/AGILE/job/AirtelWork/job/security_scan/${build_id}/api/json --user '${TOKEN}'"))
    echo "............ build status is ${status_json.building} ........."
     while (status_json.building) {
       echo "................security scan is running ....................."
        sleep 60
        status_json = jsonParse(sh(returnStdout: true, script: "curl  http://jenkins-service.airtelworld.in:8080/job/AGILE/job/AirtelWork/job/security_scan/${build_id}/api/json --user '${TOKEN}'"))
   }
    echo "............... security scan result is ${status_json.result} ......................"
    if (!status_json.result.equals('SUCCESS')) {
        echo "..............security scan failed ................."
        echo "check errors in http://jenkins-service.airtelworld.in:8080/job/AGILE/job/AirtelWork/job/security_scan/${build_id}/console"
        notifytelegram_SIT("ABORTED","${project}","AT","${getGitAuthor()}","${getLastCommitHash()}","security scan failed check errors in  http://jenkins-service.airtelworld.in:8080/job/AGILE/job/AirtelWork/job/security\\_scan/${build_id}/console")
        currentBuild.result = 'ABORTED'
        throw new RuntimeException("security scan failed")
      }
  }
}

def cleanupdata() {
  sh "sudo chown -R jenkins:jenkins ${workspace}*"
    /* clean up our workspace */
  deleteDir()
    /* clean up tmp directory */
  dir("${workspace}@tmp") {
     deleteDir()
  }
     /* clean up script directory */
  dir("${workspace}@script") {
     deleteDir()
  }
  properties([ buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '6')), ])
}

def approval(stage, service, lob) {
  LOB_DB_ENVIRONMENT = "prod"
  if(stage == '20' && lob == "AirtelWork") {
    timeout(time: 5, unit: "DAYS") {
      operators = ["A1YL1Y4M","B0214582","B0210216","B0211218","B0218629","B0203559","B0221401","A1KIOPZ0","A1Z51NXO","A1587PYB","A1D4EC9C","B0224382","A1GVHDC0","B0223310","A1RJDOPL","A1803YX2","B0224387","B0223562","B0227571","A18C096P","A1YRBSNA","B0232430","A1FBSXVA","A1P7UWXG", "B0229060", "B0226303","B0232369", "A18UM4GP","B0224069","B0228533","A1E6EAIK","A1WGRKZ5","B0212214","B0284095","A1BR6BCE","B0284570","B0227445","A1BCFBTS","A1NRD7DQ","A1IP969N","B0263315","B0269680","B0268336", "A15MVDL2", "A16O88S4", "A1E83HDP", "A1ZIX3WK", "A1699021", "A11YUPUS"]
      build_approver = ""
      while (!operators.contains(build_approver)) {
          def message = input(message: "Deploy to Production ?",
          submitterParameter: 'approver',)
          build_approver = "${message}".toString()
          echo "Approval submitted by ${build_approver}"
      };
    }
  } else if(stage == '40' && lob == "AirtelWork") {
      i = 1;
      while(i < 6) {
        auto_approve_flag = sh(script: """docker run -i core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/mongodb:4.2.4-debian-10-r0 mongo 10.5.31.144:27017/Deployment_data --username mongoadmin --password airtel123 --authenticationDatabase admin --eval 'db.Deployment_stats_${lob}_${LOB_DB_ENVIRONMENT}.find({service: "${service}",deployment_stage: "20%",build_number: "${env.BUILD_NUMBER}"},{\"proceed_deployment\":-1,\"_id\":0}).sort({\"deployment_time\": -1}).limit(1).pretty()' | awk '{print \$4}' | tail -1 | sed 's/\"//g'""", returnStdout: true).trim()
        echo "The auto approval flag is: ${auto_approve_flag}"
        if(!auto_approve_flag.contains('true')) {
           echo "The deployment is yet not good to proceed, waiting for 15 minutes before trying again.."
           sleep 900
        } else {
            echo "The deployment is now good to proceed..."
            break;
        }
        if(i > 4) {
            echo "The deployment is yet not good to proceed even after 1 hour. Please manually approve to proceed.."
            timeout(time: 5, unit: "DAYS") {
                operators = ["A1YL1Y4M","B0214582","B0210216","B0211218","B0218629","B0203559","B0221401","A1KIOPZ0","A1Z51NXO","A1587PYB","A1D4EC9C","B0224382","A1GVHDC0","B0223310","A1RJDOPL","A1803YX2","B0224387","B0223562","B0227571","A18C096P","A1YRBSNA","B0232430","A1FBSXVA","A1P7UWXG", "B0229060", "B0226303","B0232369", "A18UM4GP","B0224069","B0228533","A1E6EAIK","A1WGRKZ5","B0212214","B0284095","A1BR6BCE","B0284570","B0227445","A1BCFBTS","A1NRD7DQ","A1IP969N","B0263315","B0269680","B0268336", "A15MVDL2", "A16O88S4", "A1E83HDP", "A1ZIX3WK", "A1699021", "A11YUPUS"]
                build_approver = ""
                while (!operators.contains(build_approver)) {
                    def message = input(message: "Deploy to Production ?",
                    submitterParameter: 'approver',)
                    build_approver = "${message}".toString()
                    echo "Approval submitted by ${build_approver}"
                };
            }
        }
        i=i+1;
      }
  } else if(stage == '60' && lob == "AirtelWork") {
      i = 1;
      while(i < 6) {
        auto_approve_flag = sh(script: """docker run -i core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/mongodb:4.2.4-debian-10-r0 mongo 10.5.31.144:27017/Deployment_data --username mongoadmin --password airtel123 --authenticationDatabase admin --eval 'db.Deployment_stats_${lob}_${LOB_DB_ENVIRONMENT}.find({service: "${service}",deployment_stage: "40%",build_number: "${env.BUILD_NUMBER}"},{\"proceed_deployment\":-1,\"_id\":0}).sort({\"deployment_time\": -1}).limit(1).pretty()' | awk '{print \$4}' | tail -1 | sed 's/\"//g'""", returnStdout: true).trim()
        echo "The auto approval flag is: ${auto_approve_flag}"
        if(!auto_approve_flag.contains('true')) {
           echo "The deployment is yet not good to proceed, waiting for 15 minutes before trying again.."
           sleep 900
        } else {
            echo "The deployment is now good to proceed..."
            break;
        }
        if(i > 4) {
            echo "The deployment is yet not good to proceed even after 1 hour. Please manually approve to proceed.."
            timeout(time: 5, unit: "DAYS") {
                operators = ["A1YL1Y4M","B0214582","B0210216","B0211218","B0218629","B0203559","B0221401","A1KIOPZ0","A1Z51NXO","A1587PYB","A1D4EC9C","B0224382","A1GVHDC0","B0223310","A1RJDOPL","A1803YX2","B0224387","B0223562","B0227571","A18C096P","A1YRBSNA","B0232430","A1FBSXVA","A1P7UWXG", "B0229060", "B0226303","B0232369", "A18UM4GP","B0224069","B0228533","A1E6EAIK","A1WGRKZ5","B0212214","B0284095","A1BR6BCE","B0284570","B0227445","A1BCFBTS","A1NRD7DQ","A1IP969N","B0263315","B0269680","B0268336", "A15MVDL2", "A16O88S4", "A1E83HDP", "A1ZIX3WK", "A1699021", "A11YUPUS"]
                build_approver = ""
                while (!operators.contains(build_approver)) {
                    def message = input(message: "Deploy to Production ?",
                    submitterParameter: 'approver',)
                    build_approver = "${message}".toString()
                    echo "Approval submitted by ${build_approver}"
                };
            }
        }
        i=i+1;
      }
  } else if(stage == '100' && lob == "AirtelWork") {
      i = 1;
      while(i < 6) {
        auto_approve_flag = sh(script: """docker run -i core.harbor.cloudapps.okdcloud.india.airtel.itm/library/bitnami/mongodb:4.2.4-debian-10-r0 mongo 10.5.31.144:27017/Deployment_data --username mongoadmin --password airtel123 --authenticationDatabase admin --eval 'db.Deployment_stats_${lob}_${LOB_DB_ENVIRONMENT}.find({service: "${service}",deployment_stage: "60%",build_number: "${env.BUILD_NUMBER}"},{\"proceed_deployment\":-1,\"_id\":0}).sort({\"deployment_time\": -1}).limit(1).pretty()' | awk '{print \$4}' | tail -1 | sed 's/\"//g'""", returnStdout: true).trim()
        echo "The auto approval flag is: ${auto_approve_flag}"
        if(!auto_approve_flag.contains('true')) {
           echo "The deployment is yet not good to proceed, waiting for 15 minutes before trying again.."
           sleep 900
        } else {
            echo "The deployment is now good to proceed..."
            break;
        }
        if(i > 4) {
            echo "The deployment is yet not good to proceed even after 1 hour. Please manually approve to proceed.."
            timeout(time: 5, unit: "DAYS") {
                operators = ["A1YL1Y4M","B0214582","B0210216","B0211218","B0218629","B0203559","B0221401","A1KIOPZ0","A1Z51NXO","A1587PYB","A1D4EC9C","B0224382","A1GVHDC0","B0223310","A1RJDOPL","A1803YX2","B0224387","B0223562","B0227571","A18C096P","A1YRBSNA","B0232430","A1FBSXVA","A1P7UWXG", "B0229060", "B0226303","B0232369", "A18UM4GP","B0224069","B0228533","A1E6EAIK","A1WGRKZ5","B0212214","B0284095","A1BR6BCE","B0284570","B0227445","A1BCFBTS","A1NRD7DQ","A1IP969N","B0263315","B0269680","B0268336", "A15MVDL2", "A16O88S4", "A1E83HDP", "A1ZIX3WK", "A1699021", "A11YUPUS"]
                build_approver = ""
                while (!operators.contains(build_approver)) {
                    def message = input(message: "Deploy to Production ?",
                    submitterParameter: 'approver',)
                    build_approver = "${message}".toString()
                    echo "Approval submitted by ${build_approver}"
                };
            }
        }
        i=i+1;
      }
  } else {
      timeout(time: 5, unit: "DAYS") {
      operators = ["A1YL1Y4M","B0214582","B0210216","B0211218","B0218629","B0203559","B0221401","A1KIOPZ0","A1Z51NXO","A1587PYB","A1D4EC9C","B0224382","A1GVHDC0","B0223310","A1RJDOPL","A1803YX2","B0224387","B0223562","B0227571","A18C096P","A1YRBSNA","B0232430","A1FBSXVA","A1P7UWXG", "B0229060", "B0226303","B0232369", "A18UM4GP","B0224069","B0228533","A1E6EAIK","A1WGRKZ5","B0212214","B0284095","A1BR6BCE","B0284570","B0227445","A1BCFBTS","A1NRD7DQ","A1IP969N","B0263315","B0269680","B0268336", "A15MVDL2", "A16O88S4", "A1E83HDP", "A1ZIX3WK", "A1699021", "A11YUPUS"]
      build_approver = ""
      while (!operators.contains(build_approver)) {
          def message = input(message: "Deploy to Production ?",
          submitterParameter: 'approver',)
          build_approver = "${message}".toString()
          echo "Approval submitted by ${build_approver}"
      };
     }
  }
}

def extract_reports() {
      try {
      sh 'ln -s target/surefire-reports/*.xml $WORKSPACE'
      minimumClassCoverage = "${app_filler.minimumClassCoverage}"
      maximumClassCoverage = "${app_filler.maximumClassCoverage}"
      step([$class: 'JacocoPublisher',
            execPattern: 'target/*.exec',
            classPattern: 'target/classes',
            sourcePattern: 'src/main/java',
            exclusionPattern: 'src/test*',
            changeBuildStatus: true,
            minimumClassCoverage: "${minimumClassCoverage}",
            maximumClassCoverage: "${maximumClassCoverage}"
      ])
  }
  catch (err) {
        echo "Reports are not created"
    }
  finally {
  if (currentBuild.result == 'FAILURE') {
            error("Test coverage is too low from set threshold of  ${minimumClassCoverage}%")
    }
  }
}

def load_config() {
    echo "Loading configuration for application deployment"
    platforms_map = jsonParse(readFile("sandbox/platform/${git_proj_key.toLowerCase()}.json"))
    echo "${platforms_map.environments.keySet()}"
    sh "cp -Prf sandbox/config/* ."
    sh "cp -Prf sandbox/config/* sandbox/"
    sh "unzip sandbox/appd/*.zip  -d AppServerAgent"
    JENKINS_WAR_PATH = sh returnStdout: true, script:"cat Dockerfile | grep -E 'WAR_FILE=|JAR_FILE=' | cut -d '=' -f 2"
    PROP_RESOURCE_PATH = sh (script: "grep resources Dockerfile |  grep 'kubernetes.properties' | cut -d ' ' -f 2 | sed -e 's/\\/[^\\/]*\$//'", returnStdout: true).trim()
    REGISTRY_CREDENTIALS = "${platforms_map.registry.credentials}"
    REGISTRY_URL = "${platforms_map.registry.url}"
    REGISTRY_URL_GCP ="asia-south2-docker.pkg.dev"
    WEBLOGICCLASSPATH="/data/jenkins/plugins/weblogic-deployer-plugin/WEB-INF/lib"

    // Add Json validation in commit process
    app_filler = jsonParse(readFile('k8s.json'))
    app_resources = jsonParse(readFile("infrastructure/resources/${project}.json"))
    try{
    ci_pipeline = yamlParse(".chopper.ci")
    echo ci_pipeline.toString()
    }
    catch (err) {
      echo "Error in yaml parse ${err}"
    }


    if (env.BRANCH_NAME == "master") {
      // VERSION CHECK
      version = "${app_filler.version}"
      gitTag = "v${version}"
      imageTag = "${git_proj_key.toLowerCase()}/${project}/${env.BRANCH_NAME.replace("/", "").toLowerCase()}:${gitTag}"
      imageName = "${git_proj_key.toLowerCase()}/${project}/${env.BRANCH_NAME.replace("/", "").toLowerCase()}"            
      if (validateGitVersionReleaseTag(version)){
            currentBuild.result = 'ABORTED'
            echo ":::::::::::::Release VERSION already exists:::::::::::::"
            throw new RuntimeException("Release VERSION already exists")
        }
      // Checking if image is available in harbor
      image_id = sh (script: "docker pull ${REGISTRY_URL}/${imageTag} > /dev/null && echo 'true' || echo 'false'", returnStdout: true).trim()
      echo "image is ${image_id}"
    }
    else if (env.CHANGE_TARGET != null) {
    version = "${env.BRANCH_NAME.replace('/', '').toLowerCase()}-${app_filler.version}-${getLastCommitHash()}-${env.BUILD_NUMBER}"
    gitTag = "v${version}"
    imageTag = "${git_proj_key.toLowerCase()}/${project}/pr:${gitTag}"
    imageName = "${git_proj_key.toLowerCase()}/${project}/${env.BRANCH_NAME.replace("/", "").toLowerCase()}"
    }
    else {
    version = "${app_filler.version}-${getLastCommitHash()}-${env.BUILD_NUMBER}"
    gitTag = "v${version}"
    imageTag = "${git_proj_key.toLowerCase()}/${project}/${env.BRANCH_NAME.replace("/", "").toLowerCase()}:${gitTag}"
    imageName = "${git_proj_key.toLowerCase()}/${project}/${env.BRANCH_NAME.replace("/", "").toLowerCase()}"
    echo "image is ${image_id}"
  }
}

def vaultFetch(project, environment, service, secret_path, writefile) {
    if (secret_path == "vault") {
      library "shared-library"
      response = vaultFetch(project, environment, service)
      if(writefile) {
        sh "mkdir -p infrastructure/secrets"
        dir("infrastructure/secrets") {
          data = readJSON text: '{}'
          data."${environment}" = response
          writeJSON file: "${service}.json", json: data, pretty: 4
        }
      return response
    }
  }
}

def imageCheck() {
  size = sh returnStdout: true, script:"docker images | grep ${imageName} | awk '{print \$7}'"
  actualsize = sh returnStdout: true, script:"echo '${size}' | sed 's/MB//g' "
  echo"actualsize is ${actualsize}"
  if('${actualsize}' > '1024' )
  {
    error("Build failed because image size is greater than 1GB")
  }
}
// ------------------------------------------------------------------------------------------------------------------------
// [START] Ansible Deploy
// ------------------------------------------------------------------------------------------------------------------------


// ========================================================================================================================
// [START] MAIN FUNCTION
// ========================================================================================================================

def start(testCommand, slackUser = "", slackChannel = "", coverageCommand = "") {
  def project = "${env.JOB_NAME}".tokenize("/")[-2];
  def lob = "${env.JOB_NAME}".tokenize("/")[-4];
  def project_base = "${env.JOB_NAME}".tokenize("/")[-3];
  def messageParams = ": ${project} on branch ${env.BRANCH_NAME} with build #${env.BUILD_NUMBER}"
  def app
  def slackNotificationChannel = slackChannel
  if (project.contains("-ui-")) { def isStaticUI = true}
  else { def isStaticUI = false }
  //Node Selection
  //echo "Lob is: ${lob}"
  //if (lob.matches('AirtelWork|AIRFF|AIRFFT|AIRL|AIRFD')) { worker = "slave5" }
  //else if (lob.matches('ECAF|SLMITRA|RETAPP|mitra')) { worker = "slave1" }
  //else { worker = "slave2" }
  //echo "Worker name is: ${worker}"
  //Node Selection
  echo "Lob is: ${lob}"
  if (lob.matches('AirtelWork') && env.BRANCH_NAME == "master") { worker = "airw_services" }
  //else if (lob.matches('AirtelWork') && env.BRANCH_NAME == "master" && isStaticUI == "true") { worker = "slave5" }
  else if (lob.matches('AirtelWork') && env.BRANCH_NAME != "master") { worker = "airw_services" }
  //else if (lob.matches('AirtelWork') && env.BRANCH_NAME != "master" && isStaticUI == "true") { worker = "slave5" }
  else if (lob.matches('AIRFF|AIRFFT|AIRL|AIRFD|LAD')) { worker = "slave5" }
  else if (lob.matches('ECAF|SLMITRA|RETAPP|mitra')) { worker = "slave1" }
  else { worker = "slave2" }
  echo "Worker name is: ${worker}"

  // echo ':::::::::::::[ Pipeline Initialize from AirteL-X-Labs (ProductEngineering) ]:::::::::::::'
  node(worker) {
        stage('Checkout'){
            cleanupdata()
            checkoutcode()
        }

       //----------------------------------------------------------------------------------------------------
       // [START] Loading Configuration from files
       //----------------------------------------------------------------------------------------------------
       stage('Load-Config') {
        load_config()
       }
       //----------------------------------------------------------------------------------------------------
       // [END] Loading Configuration from files
       //----------------------------------------------------------------------------------------------------

        // Adding a check to ensure that releasev3 branch has jacoco agent integrated
       if (env.BRANCH_NAME.toLowerCase().matches("releasev3") && lob == "AirtelWork" && !isStaticUI && !isPython) {
            stage('Validate jacoco agent integration') {
                //echo "[START] Validating jacoco agent integration"
                try {
                    startup_command = sh(returnStdout: true, script: "cat startup.sh")
                    if (startup_command.contains('org.jacoco.agent')) {
                        //echo "The startup script has jacoco intergated in it, good to proceed!!"
                    } else {
                        error("The startup script does not have jacoco intergated in it, please add before proceeding!!")
                    }
                }
                catch (err) {
                    msg = ""
                    throw err
                }
            }
        }

       //----------------------------------------------------------------------------------------------------
       // [START] Building/compiling codebase
       //----------------------------------------------------------------------------------------------------\

       stage('Code Quality check') {
         try {
            run_pmd()
            code_quality()
         } catch (err) {}
       }

       stage('Build') {
         if (env.BRANCH_NAME.toLowerCase().matches("master") && image_id == "true" && lob == "AirtelWork")
         {
           //echo "Build/Compile not required as image is available in repository"
         }
         else
         {
            //echo "Compiling and packaging application"
            try {
               sh "make build"
            }
            catch (err) {
              // notify("Source Compile","FAILED","${project}",env.BRANCH_NAME,platforms_map.teams_webhook)
               throw err
            }
            sh "sudo chown -R jenkins:root ${workspace}*"
            // Package into container
            try {
                sh "cp /usr/share/zoneinfo/Asia/Kolkata ."
                app = docker.build("${imageTag}");
            }
            catch (err) {
           // notify("Containerization","FAILED","${project}",env.BRANCH_NAME,platforms_map.teams_webhook)
            throw err
           }
         }

       }

      if (env.BRANCH_NAME.toLowerCase().matches("master") && lob == "AirtelWork")
      {
        stage ("Check image size") {
                //echo"check image size"
                imageCheck()
        }
      }
       stage ("Extract reports") {
          extract_reports()
         }

       stage('Security') {
           echo "Checking Code for Security Vulnerabilities"
               try{
                  library "security-library"
                  if ((lob.matches("AirtelWork") && env.BRANCH_NAME.toLowerCase().matches("releasev3") && project != "mock-server-service") || (lob.matches("ECAF|mitra|MBOSS|GOAL|PROMOTER") && env.BRANCH_NAME.toLowerCase().matches("master"))) {
                  security_check()
                  //sh "docker run  --env https_proxy='http://KAW00001:Alpha%40123@airtelproxy.airtel.com:4145' --rm -v /var/run/docker.sock:/var/run/docker.sock -v ${HOME}/.cache:/root/.cache/ core.harbor.cloudapps.okdcloud.india.airtel.itm/library/aquasec/trivy --exit-code 0 --severity CRITICAL,HIGH ${imageTag}"
                  }
               }
               catch(err)
               {
                  throw err
               }
       }

       //----------------------------------------------------------------------------------------------------
       // [END] Building/compiling codebase
       //----------------------------------------------------------------------------------------------------

  //  if (env.CHANGE_TARGET != null) {
  //    if (env.CHANGE_TARGET.toLowerCase().matches("master|develop|release|releaseV2|releaseV3|integration")) {

     //     switch (env.CHANGE_TARGET) {
     //       case "develop":
     //           echo "PR to develop"
     //           break;
     //       case "release":
     //           echo "PR to release"
     //           break;
     //       case "releaseV2":
     //           echo "PR to releaseV2"
     //           break;
     //       case "releaseV3":
     //           echo "PR to releaseV2"
     //           break;
     //       case "integration":
     //           echo "PR to integration"
     //           break;
     //       case "master":
     //           echo "PR to master"
     //           break;
     //     }

      //}
    //}


    if (env.BRANCH_NAME.toLowerCase().matches("master|develop|release|releasev2|releasev3|integration|release_beta|performance")) {
      if (image_id != "true"){

        //----------------------------------------------------------------------------------------------------
        // [START] Uploading packages to repository
        //----------------------------------------------------------------------------------------------------
        stage('Publish Build') {
                  ZIPNAME = "${project}-${gitTag}.zip"
                  //echo "ZIPNAME is ${ZIPNAME}"
                  Folder_Name = sh returnStdout: true, script:"echo ${imageTag} | cut -d'/' -f 1"
                  Folder_Name = Folder_Name.trim()
                  if (!isStaticUI && !isPython){
                     echo "[START] Publishing application package"
                     try {
                          //pushing docker image to google articatry registry
                          echo "Pushing image to gcp artifact registry"
                          if (lob.matches("AirtelWork")) {
                            sh "docker tag ${imageTag} ${REGISTRY_URL_GCP}/prj-cloudops-devops-services/${imageTag}"
                            sh "docker push ${REGISTRY_URL_GCP}/prj-cloudops-devops-services/${imageTag}"
                          }
                          //pushing docker image to on-prem harbor
                          echo "Pushing image to on-prem harbor"
                          docker.withRegistry("http://${REGISTRY_URL}", "${REGISTRY_CREDENTIALS}") {
                                 docker.image(imageTag).push() }
                          }
                     catch (err) {
                       println("error while pushing image " + err)
                       throw err
                     }
                    finally {
                     sh ("docker rmi ${REGISTRY_URL}/${imageTag}")
                     sh ("docker rmi ${imageTag}")
                      try { sh ("docker rmi ${REGISTRY_URL_GCP}/prj-cloudops-devops-services/${imageTag}") } catch (err) {}
                    }
                     //echo "Compressing war/jar"

                     try {
                     JENKINS_WAR_PATH = JENKINS_WAR_PATH.trim()
                     //echo "path is ${JENKINS_WAR_PATH}"
                     sh "cp -rf '${WORKSPACE}/'${JENKINS_WAR_PATH} '${WORKSPACE}'"
                     dir("${workspace}") {
                     sh "zip -qr ${ZIPNAME} *.war || zip -qr ${ZIPNAME} *.jar"
                     //echo "Uploading file to Nexus"
                     sh "curl -v -u agileairtel:airtel123 --upload-file ${ZIPNAME} http://nexus.airtelworld.in:8081/repository/Agile/${Folder_Name}/${project}/${env.BRANCH_NAME}/${ZIPNAME}"
                     sleep 5
                     sh "rm -f ${ZIPNAME}"
                     }
                     }
                     catch (err) {
                        // notify("Publish War/Jar to nexus: ","FAILED","${project}",env.BRANCH_NAME,platforms_map.teams_webhook)
                         sh "rm -f ${ZIPNAME}"
                         throw err
                     }
                     }
                  else if (isPython) {
                    //echo "[START] Publishing application package"
                     try {
                          docker.withRegistry("http://${REGISTRY_URL}", "${REGISTRY_CREDENTIALS}") {
                                 docker.image(imageTag).push()}
                          }
                     catch (err) {
                         docker.withRegistry("http://${REGISTRY_URL}", "${REGISTRY_CREDENTIALS}") {
                                 docker.image(imageTag).push()}
                         notify("Publish Image: ","FAILED","${project}",env.BRANCH_NAME,platforms_map.teams_webhook)
                         sh ("docker rmi ${REGISTRY_URL}/${imageTag}")
                         sh ("docker rmi ${imageTag}")
                       sh ("docker rmi ${REGISTRY_URL_GCP}/prj-cloudops-devops-services/${imageTag}")
                         throw err
                     }
                     sh ("docker rmi ${REGISTRY_URL}/${imageTag}")
                     sh ("docker rmi ${imageTag}")
                    try { sh ("docker rmi ${REGISTRY_URL_GCP}/prj-cloudops-devops-services/${imageTag}") } catch (err) {}
                     //echo "Compressing war/jar"
                    try {
                    dir("${workspace}") {
                    sh "zip -qr ${ZIPNAME} src/"
                    //echo "Uploading file to Nexus"
                    sh "curl -v -u agileairtel:airtel123 --upload-file ${ZIPNAME} http://nexus.airtelworld.in:8081/repository/Agile/${Folder_Name}/${project}/${env.BRANCH_NAME}/${ZIPNAME}"
                    sleep 5
                    sh "rm -f ${ZIPNAME}"
                    }
                    }
                  catch (err) {
                        // notify("Publish War/Jar to nexus: ","FAILED","${project}",env.BRANCH_NAME,platforms_map.teams_webhook)
                         sh "rm -f ${ZIPNAME}"
                         throw err
                     }
                }
                else{
                    try {
                    dir("${workspace}") {
                    sh "zip -qr ${ZIPNAME} build/"
                    //echo "Uploading file to Nexus"
                    sh "curl -v -u agileairtel:airtel123 --upload-file ${ZIPNAME} http://nexus.airtelworld.in:8081/repository/Agile/${Folder_Name}/${project}/${env.BRANCH_NAME}/${ZIPNAME}"
                    sleep 5
                    sh "rm -f ${ZIPNAME}"
                    }
                    }
                  catch (err) {
                        // notify("Publish War/Jar to nexus: ","FAILED","${project}",env.BRANCH_NAME,platforms_map.teams_webhook)
                         sh "rm -f ${ZIPNAME}"
                         throw err
                     }
                }
        }
      }
      else{
        echo "Image already available"
      }
        //---------------------------------------------------------------------------------------------------
        // [END] Uploading packages to repository
        //---------------------------------------------------------------------------------------------------
        switch (env.BRANCH_NAME) {
          case "develop":
              milestone 2
              stage('Deploy - DEV') {
                     //----------------------------------------------------------------------------------------------------
                     // Test cluster deployment
                     //----------------------------------------------------------------------------------------------------
                     echo "Development Deployment"
                     DEPLOYMENT_MODE = "${platforms_map.environments.development.config_map}"
                     DEPLOYMENT_SCRIPT = "${platforms_map.environments.development.ansible.playbook.api}"
                     INVENTORY = "${platforms_map.environments.development.ansible.inventory}"         
                     try {
                         if (isStaticUI == false ) {
                          if (DEPLOYMENT_MODE == "ansible" || DEPLOYMENT_MODE == "vault") {
                             print("creating properties files for deployment")
                             vaultFetch(git_proj_key.toLowerCase(), "development", project, DEPLOYMENT_MODE, "true")
                             sh "cp -rvf sandbox/* ."
                             app = docker.build("secrets");
                             app.inside {
                             sh("python services/refreshSecrets.py -p ${git_proj_key.toLowerCase()} -s ${project} -e development -i ${INVENTORY} -t development")
                              }
                             ansible_deploy("development",
                                             "${DEPLOYMENT_SCRIPT}",
                                            "${git_proj_key.toLowerCase()}_${project}_sit",
                                            "-e 'WEBLOGICCLASSPATH=${WEBLOGICCLASSPATH}' -e 'JENKINSWARPATH=${WORKSPACE}/${JENKINS_WAR_PATH}' -e 'SRC_PROP_PATH=${WORKSPACE}/${PROP_RESOURCE_PATH}/' -e 'serviceHealth=${app_filler.healthCheckPath}' -e 'service_port=${app_filler.port}'")
                            }
                            else {
                            deploy(
                                    "development",
                                    git_proj_key.toLowerCase(),
                                    "sit",
                                    messageParams as Object,
                                    slackNotificationChannel,
                                    slackUser,
                            )
                          }
                         }
                         else{
                            if (lob.matches('AirtelWork')) {
                             nginx_target = "nginx_dev"
                           } else if (lob.matches('LAD')) { 
                              nginx_target = "nginx_sit_lad"
                           } else {   
                               nginx_target = "nginx_dev_old"
                           }
                              ansible_deploy("development",
                                             "web_static_deployment.yml",
                                             "${nginx_target}",
                                             "-e 'service=${project}' -e 'source=${WORKSPACE}/build/' -e 'destination=/var/www/${git_proj_key.toLowerCase()}/development' -e 'webversion=${app_filler["x-airtelwork-webversion"]}' -e 'conf_file=/etc/nginx/conf.d/airtelwork.conf'")

                         }

                       }
                       catch (err) {
                        //  notify("Application Deployment","FAILED","${project}","DEVELOPMENT",platforms_map.teams_webhook)
                         if (lob.matches('AirtelWork')) {
                           notifytelegram_SIT("FAILED","${project}","development","${getGitAuthor()}","${getLastCommitHash()}","Failed")
                         }
                          throw err
                       }

                     //----------------------------------------------------------------------------------------------------
                     // Test cluster deployment
                     //----------------------------------------------------------------------------------------------------
              }
              break;
          case ["releasev2"]:
              milestone 3
              stage('Deploy - SIT') {
                      try{
                     //----------------------------------------------------------------------------------------------------
                     // Test cluster deployment
                     //----------------------------------------------------------------------------------------------------
                         environment = "sit2"
                         if (isStaticUI == false ){
                         deploy(
                                 environment,
                                 git_proj_key.toLowerCase(),
                                 "20% production",
                                 messageParams as Object,
                                 slackNotificationChannel,
                                 slackUser,
                         )}
                         else{
                              ansible_deploy("development",
                                             "web_static_deployment.yml",
                                             "nginx_dev_old",
                                             "-e 'service=${project}' -e 'source=${WORKSPACE}/build/' -e 'destination=/var/www/${git_proj_key.toLowerCase()}/sit2' -e 'webversion=${app_filler["x-airtelwork-webversion"]}' -e 'conf_file=/etc/nginx/conf.d/airtelwork.conf'")

                         }
                     }
                     catch (err){
                          notify("Application Deployment","FAILED","${project}","SITv2",platforms_map.teams_webhook)
                        //  notifytelegram_SIT("FAILED","${project}","SITv2","${getGitAuthor}","${getCommitLOG}")
                          throw err
                     }
                    // notify("Application Deployment ","SUCCESS","${project}","SITv2",platforms_map.teams_webhook)
                     //----------------------------------------------------------------------------------------------------
                     // SIT cluster deployment
                     //----------------------------------------------------------------------------------------------------
              }

              break;
              case ["releasev3","performance"]:
              milestone 4
              stage('Deploy - SIT') {
                      try{
                     //----------------------------------------------------------------------------------------------------
                     // Test cluster deployment
                     //----------------------------------------------------------------------------------------------------
                         environment = "sit3"
                         if (isStaticUI == false ){
                         deploy(
                                 environment,
                                 git_proj_key.toLowerCase(),
                                 "20% production",
                                 messageParams as Object,
                                 slackNotificationChannel,
                                 slackUser,
                         )}
                         else{
                              ansible_deploy("development",
                                             "web_static_deployment.yml",
                                             "nginx_at",
                                             "-e 'service=${project}' -e 'source=${WORKSPACE}/build/' -e 'destination=/var/www/${git_proj_key.toLowerCase()}/sit3' -e 'webversion=${app_filler["x-airtelwork-webversion"]}' -e 'conf_file=/etc/nginx/conf.d/airtelwork.conf'")

                         }
                     }
                     catch (err){
                          //notify("Application Deployment","FAILED","${project}","SITv3",platforms_map.teams_webhook)
                     //     notifytelegram_SIT("FAILED","${project}","SITv3","${getGitAuthor}","${getCommitLOG}")
                          throw err
                     }
                     //notify("Application Deployment ","SUCCESS","${project}","SITv3",platforms_map.teams_webhook)
                     //----------------------------------------------------------------------------------------------------
                     // SIT cluster deployment
                     //----------------------------------------------------------------------------------------------------
              }

              break;
          case ["release","integration"]:
              milestone 5
              stage('Deploy - SIT') {
                      try{
                     //----------------------------------------------------------------------------------------------------
                     // Test cluster deployment
                     //----------------------------------------------------------------------------------------------------
                         environment = "sit"
                         if (isStaticUI == false ){
                         deploy(
                                 environment,
                                 git_proj_key.toLowerCase(),
                                 "20% production",
                                 messageParams as Object,
                                 slackNotificationChannel,
                                 slackUser,
                         )}
                         else{
                            if (lob.matches('AirtelWork')) {
                             nginx_target = "nginx_dev"
                           } else {    
                               nginx_target = "nginx_dev_old"
                           }
                              ansible_deploy("development",
                                             "web_static_deployment.yml",
                                             "${nginx_target}",
                                             "-e 'service=${project}' -e 'source=${WORKSPACE}/build/' -e 'destination=/var/www/${git_proj_key.toLowerCase()}/sit' -e 'webversion=${app_filler["x-airtelwork-webversion"]}' -e 'conf_file=/etc/nginx/conf.d/airtelwork.conf'")

                         }
                         DB_insert("${project}", "sit", "${gitTag}","success","false","${env.BUILD_NUMBER}","completed","${lob}","${environment}")     
                     }
                     catch (err){
                          //notify("Application Deployment","FAILED","${project}","SIT",platforms_map.teams_webhook)
                          DB_insert("${project}", "sit", "${gitTag}","failed","false","${env.BUILD_NUMBER}","failed","${lob}","${environment}")  
                          if (lob.matches('AirtelWork')) {
                              notifytelegram_SIT("FAILED","${project}","SIT","${getGitAuthor()}","${getLastCommitHash()}","failed")
                         }
                          throw err
                     }
                     //notify("Application Deployment ","SUCCESS","${project}","SIT",platforms_map.teams_webhook)
                     //----------------------------------------------------------------------------------------------------
                     // SIT cluster deployment
                     //----------------------------------------------------------------------------------------------------
              }

              break;
          case "release_beta":
              milestone 6
              stage('Deploy - Beta') {
                     //----------------------------------------------------------------------------------------------------
                     // Test cluster deployment
                     //----------------------------------------------------------------------------------------------------
                     DEPLOYMENT_MODE = "${platforms_map.environments.beta.config_map}"
                     DEPLOYMENT_SCRIPT = "${platforms_map.environments.beta.ansible.playbook.api}"
                     INVENTORY = "${platforms_map.environments.beta.ansible.inventory}"
                      try {
                         if (isStaticUI == false){
                           if (DEPLOYMENT_MODE == "ansible") {
                            if (!isPython) {
                              print("creating properties files for deployment")
                              vaultFetch(git_proj_key.toLowerCase(), "beta", project, DEPLOYMENT_MODE, "true")
                              sh "cp -rvf sandbox/* ."
                                app = docker.build("secrets");
                                app.inside {
                                 sh("python services/refreshSecrets.py -p ${git_proj_key.toLowerCase()} -s ${project} -e beta -i ${INVENTORY} -t ${project}_beta")
                               }
                            }
                             ansible_deploy("production_${git_proj_key.toLowerCase()}",
                                             "${DEPLOYMENT_SCRIPT}",
                                            "${project}_beta",
                                            "-e 'WEBLOGICCLASSPATH=${WEBLOGICCLASSPATH}' -e 'JENKINSWARPATH=${WORKSPACE}/${JENKINS_WAR_PATH}' -e 'SRC_PROP_PATH=${WORKSPACE}/${PROP_RESOURCE_PATH}/' -e 'serviceHealth=${app_filler.healthCheckPath}' -e 'service_port=${app_filler.port}'")
                            }
                            else {
                            deploy(
                                    "beta",
                                    git_proj_key.toLowerCase(),
                                    "production",
                                    messageParams as Object,
                                    slackNotificationChannel,
                                    slackUser,
                            )
                          }
                         }  
                         else{
                              ansible_deploy("production_${git_proj_key.toLowerCase()}",
                                             "web_static_deployment.yml",
                                             "nginx_beta",
                                             "-e 'service=${project}' -e 'source=${WORKSPACE}/build/' -e 'destination=/var/www' -e 'webversion=${app_filler["x-airtelwork-webversion"]}' -e 'conf_file=/etc/nginx/conf.d/default.conf'")
                         }
                       }
                       catch (err) {
                          //notify("Application Deployment","FAILED","${project}","DEVELOPMENT",platforms_map.teams_webhook)
                          throw err
                       }
                     //----------------------------------------------------------------------------------------------------
                     // Test cluster deployment
                     //----------------------------------------------------------------------------------------------------
              }
          break;
          case "master":
              milestone 7
              stage('Deploy - Beta') {
                     //----------------------------------------------------------------------------------------------------
                     // Test cluster deployment
                     //----------------------------------------------------------------------------------------------------
                     DEPLOYMENT_MODE = "${platforms_map.environments.beta.config_map}"
                     DEPLOYMENT_SCRIPT = "${platforms_map.environments.beta.ansible.playbook.api}"
                     INVENTORY = "${platforms_map.environments.beta.ansible.inventory}"
                      try {
                         if (isStaticUI == false && isPython == false){
                           if (DEPLOYMENT_MODE == "ansible") {
                           //  print("creating properties files for deployment")
                           //  sh "cp -rvf sandbox/* ."
                           //   app = docker.build("secrets");
                           //   app.inside {
                           //   sh("python services/refreshSecrets.py -p ${git_proj_key.toLowerCase()} -s ${project} -e beta -i ${INVENTORY} -t ${project}_beta")
                  //  }
                             ansible_deploy("production_${git_proj_key.toLowerCase()}",
                                             "${DEPLOYMENT_SCRIPT}",
                                            "${project}_betaa",
                                            "-e 'WEBLOGICCLASSPATH=${WEBLOGICCLASSPATH}' -e 'JENKINSWARPATH=${WORKSPACE}/${JENKINS_WAR_PATH}' -e 'SRC_PROP_PATH=${WORKSPACE}/${PROP_RESOURCE_PATH}/' -e 'serviceHealth=${app_filler.healthCheckPath}' -e 'service_port=${app_filler.port}'")
                            }
                            else {
                            deploy(
                                    "beta",
                                    git_proj_key.toLowerCase(),
                                    "production",
                                    messageParams as Object,
                                    slackNotificationChannel,
                                    slackUser,
                            )
                          }
                         }  else if (isPython == true) {
                           ansible_deploy("production_${git_proj_key.toLowerCase()}",
                                             "${DEPLOYMENT_SCRIPT}",
                                            "${project}_beta",
                                          "-e 'WORKSPACE=${WORKSPACE}'") }
                         else{
                           try {
                              ansible_deploy("production_${git_proj_key.toLowerCase()}",
                                             "web_static_deployment.yml",
                                             "nginx_beta",
                                             "-e 'service=${project}' -e 'source=${WORKSPACE}/build/' -e 'destination=/var/www' -e 'webversion=${app_filler["x-airtelwork-webversion"]}' -e 'conf_file=/etc/nginx/conf.d/default.conf'")
                           } catch (err) {}
                         }
                       }
                       catch (err) {
                          //notify("Application Deployment","FAILED","${project}","DEVELOPMENT",platforms_map.teams_webhook)
                          throw err
                       }
                     //----------------------------------------------------------------------------------------------------
                     // Test cluster deployment
                     //----------------------------------------------------------------------------------------------------
                }
              milestone 8
              stage('Deploy - 20% PROD') {
                  approval("20","${project}","${lob}")
                  environment = "canary"
                  DEPLOYMENT_MODE = "${platforms_map.environments.production.config_map}"
                  DEPLOYMENT_SCRIPT = "${platforms_map.environments.production.ansible.playbook.api}"
                  INVENTORY = "${platforms_map.environments.production.ansible.inventory}"
                  try {
                      //----------------------------------------------------------------------------------------------------
                      // 20% production cluster deployment
                      //----------------------------------------------------------------------------------------------------
                      if (!isStaticUI){
                      //echo "20% production deployment"
                      if (DEPLOYMENT_MODE == "ansible" || DEPLOYMENT_MODE == "vault") {
                        print("creating properties files for prod deployment")
                            vaultFetch(git_proj_key.toLowerCase(), "production", project, DEPLOYMENT_MODE, "true")
                             sh "cp -rvf sandbox/* ."
                              app = docker.build("secrets");
                              app.inside {
                              sh("python services/refreshSecrets.py -p ${git_proj_key.toLowerCase()} -s ${project} -e production -i ${INVENTORY} -t ${project}_canary")
                                        }
                            ansible_deploy("production_${git_proj_key.toLowerCase()}",
                                             "${DEPLOYMENT_SCRIPT}",
                                            "${project}_canary",
                                            "-e 'WEBLOGICCLASSPATH=${WEBLOGICCLASSPATH}' -e 'JENKINSWARPATH=${WORKSPACE}/${JENKINS_WAR_PATH}' -e 'SRC_PROP_PATH=${WORKSPACE}/${PROP_RESOURCE_PATH}/' -e 'serviceHealth=${app_filler.healthCheckPath}' -e 'service_port=${app_filler.port}'")
                            DB_insert("${project}", "20%", "${gitTag}","sucess","false","${env.BUILD_NUMBER}","inprogress","${lob}","prod")
                           }
                           else {
                              try {
                                echo "Deployment on kubernetes..."
                                DB_insert("${project}", "20%", "${gitTag}","inprogress","false","${env.BUILD_NUMBER}","inprogress","${lob}","prod")
                                deploy("canary",git_proj_key.toLowerCase(),"production",messageParams as Object,slackNotificationChannel,
                                   slackUser,
                                )
                                //deployphase1("canary",git_proj_key.toLowerCase(),"production",messageParams as Object,slackNotificationChannel,
                                //   slackUser,
                                //)
                                //notifytelegram("Application Deployment Notification","20% deployment complete!!","${project}","Production")
                                notifytelegram("%32%E2%83%A3","20","20% deployment complete!!","${project}","${gitTag}","Production")
                                DB_update("${project}", "20%", "${gitTag}","success","false","${env.BUILD_NUMBER}","inprogress","${lob}","prod")                          
                              } catch(Exception e) {
                                 // notifytelegram("Application Deployment Notification","20% deployment failed!!","${project}","Production")
                                notifytelegram("%32%E2%83%A3","20","20% deployment failed!!","${project}","${gitTag}","Production")
                                DB_update("${project}", "20%", "${gitTag}","failed","false","${env.BUILD_NUMBER}","failed","${lob}","prod")
                              }

                      }
                      }
                      else{
                                                     DB_insert("${project}", "20%", "${gitTag}","inprogress","false","${env.BUILD_NUMBER}","inprogress","${lob}","prod")
                         ansible_deploy("production_${git_proj_key.toLowerCase()}",
                                         "web_static_deployment.yml",
                                         "${project}_canary",
                                         "-e 'service=${project}' -e 'source=${WORKSPACE}/build/' -e 'destination=/var/www' -e 'webversion=${app_filler["x-airtelwork-webversion"]}' -e 'conf_file=/etc/nginx/conf.d/default.conf'")
                          if (lob == "AirtelWork") {
                             notifytelegram("%32%E2%83%A3","20","20% deployment complete!!","${project}","${gitTag}","Production")
                              DB_update("${project}", "20%", "${gitTag}","success","false","${env.BUILD_NUMBER}","inprogress","${lob}","prod")
                          }
                      }
                  }
                  catch(err)
                          {
                             notify("Application Deployment","FAILED","${project}","CANARY",platforms_map.teams_webhook)
                             throw err
                          }
                  //notify("Application Deployment","SUCCESS","${project}","CANARY",platforms_map.teams_webhook)
                      //----------------------------------------------------------------------------------------------------
                      // 20% production Cluster deployment End
                      //----------------------------------------------------------------------------------------------------
              }
              milestone 9
              stage('Deploy - 40% & 60 % PROD') {
                  try {
                      if (!isStaticUI && lob == "AirtelWork"){
                      //echo "40% production deployment"
                      approval("40","${project}","${lob}")
                      DB_update("${project}", "20%", "${gitTag}","success","false","${env.BUILD_NUMBER}","completed","${lob}","prod")
                      DB_insert("${project}", "40%", "${gitTag}","inprogress","false","${env.BUILD_NUMBER}","inprogress","${lob}","prod")
                      scaleReplicas(app_resources,git_proj_key.toLowerCase(),"${project}",40)
                      notifytelegram("%34%E2%83%A3","40","40% deployment complete!!","${project}","${gitTag}","Production")
                      DB_update("${project}", "40%", "${gitTag}","success","false","${env.BUILD_NUMBER}","inprogress","${lob}","prod") 
                      }
                 }
                  catch(err)
                          {
                             //notifytelegram("Application Deployment Notification","40% deployment failed!!","${project}","Production")
                             notifytelegram("%34%E2%83%A3","40","40% deployment failed!!","${project}","${gitTag}","Production")
                             //notify("Application Deployment","FAILED","${project}","CANARY",platforms_map.teams_webhook)
                             DB_update("${project}", "40%", "${gitTag}","failed","false","${env.BUILD_NUMBER}","completed","${lob}","prod")
                             throw err
                  } 
                  try {
                      //----------------------------------------------------------------------------------------------------
                      // 60% production cluster deployment
                      //----------------------------------------------------------------------------------------------------
                      if (!isStaticUI && lob == "AirtelWork"){
                      echo "60% production deployment"
                      approval("60","${project}","${lob}")
                      DB_update("${project}", "40%", "${gitTag}","success","false","${env.BUILD_NUMBER}","completed","${lob}","prod")                      
                      DB_insert("${project}", "60%", "${gitTag}","inprogress","false","${env.BUILD_NUMBER}","inprogress","${lob}","prod")
                      scaleReplicas(app_resources,git_proj_key.toLowerCase(),"${project}",60)
                      //scaleReplicasphase1(app_resources,git_proj_key.toLowerCase(),"${project}",60)
                      //notifytelegram("Application Deployment Notification","60% deployment complete!!","${project}","Production")
                      notifytelegram("%36%E2%83%A3","60","60% deployment complete!!","${project}","${gitTag}","Production")
                      DB_update("${project}", "60%", "${gitTag}","success","false","${env.BUILD_NUMBER}","inprogress","${lob}","prod")
                      }
                       }
                  catch(err)
                          {
                            // notifytelegram("Application Deployment Notification","60% deployment failed!!","${project}","Production")
                             notifytelegram("%36%E2%83%A3","60","60% deployment failed!!","${project}","${gitTag}","Production")
                             //notify("Application Deployment","FAILED","${project}","CANARY",platforms_map.teams_webhook)
                             DB_update("${project}", "60%", "${gitTag}","failed","false","${env.BUILD_NUMBER}","completed","${lob}","prod")
                             throw err
                  }
              }
              milestone 10
              stage('Deploy - PROD') {
                  environment = "production"
                  commitmessage = sh(returnStdout: true, script: 'git log --format=format:%s -1')
                  JIRA_ID = sh returnStdout: true, script:"echo ${commitmessage} | cut -d ' ' -f 1"
                  JIRA_ID = JIRA_ID.trim()
                  approval("100","${project}","${lob}")
                  try{
                      if (!isStaticUI){
                              //echo "Production deployment"
                              if (DEPLOYMENT_MODE == "ansible" || DEPLOYMENT_MODE == "vault") {
                                  ansible_deploy("production_${git_proj_key.toLowerCase()}",
                                             "${DEPLOYMENT_SCRIPT}",
                                            "${project}_prod",
                                            "-e 'WEBLOGICCLASSPATH=${WEBLOGICCLASSPATH}' -e 'JENKINSWARPATH=${WORKSPACE}/${JENKINS_WAR_PATH}' -e 'SRC_PROP_PATH=${WORKSPACE}/${PROP_RESOURCE_PATH}/' -e 'serviceHealth=${app_filler.healthCheckPath}' -e 'service_port=${app_filler.port}'")
                                  DB_insert("${project}", "100%", "${gitTag}","success","false","${env.BUILD_NUMBER}","compeleted","${lob}","prod")          
                           }
                           else {
                              try {
                                  //echo "Deployment on OKD..."
                                  DB_update("${project}", "60%", "${gitTag}","success","false","${env.BUILD_NUMBER}","completed","${lob}","prod")
                                  DB_insert("${project}", "100%", "${gitTag}","inprogress","false","${env.BUILD_NUMBER}","inprogress","${lob}","prod")
                                  scaleReplicas(app_resources,git_proj_key.toLowerCase(),"${project}",100)
                                  //scaleReplicasphase1(app_resources,git_proj_key.toLowerCase(),"${project}",100)
                                  deploy("production",git_proj_key.toLowerCase(),"production",messageParams as Object,slackNotificationChannel,
                                    slackUser,
                                  )
                                  //deployphase1("production",git_proj_key.toLowerCase(),"production",messageParams as Object,slackNotificationChannel,
                                  //  slackUser,
                                  //)
                                 // notifytelegram("Application Deployment Notification","100% deployment complete!!","${project}","Production")
                                  notifytelegram("%31%E2%83%A3 %30%E2%83%A3","100","100% deployment complete!!","${project}","${gitTag}","Production")
                                  DB_update("${project}", "100%", "${gitTag}","success","false","${env.BUILD_NUMBER}","completed","${lob}","prod")
                              } catch (Exception e) {
                                //notifytelegram("Application Deployment Notification","100% deployment failed!!","${project}","Production")
                                notifytelegram("%31%E2%83%A3 %30%E2%83%A3","100","100% deployment failed!!","${project}","${gitTag}","Production")
                                DB_update("${project}", "100%", "${gitTag}","failed","false","${env.BUILD_NUMBER}","completed","${lob}","prod")
                              }
                          }
                      }
                          else{
                            DB_update("${project}", "20%", "${gitTag}","success","false","${env.BUILD_NUMBER}","completed","${lob}","prod")
                            DB_insert("${project}", "100%", "${gitTag}","inprogress","false","${env.BUILD_NUMBER}","inprogress","${lob}","prod")
                             ansible_deploy("production_${git_proj_key.toLowerCase()}",
                                      "web_static_deployment.yml",
                                      "${project}_prod",
                                      "-e 'service=${project}' -e 'source=${WORKSPACE}/build/' -e 'destination=/var/www' -e 'webversion=${app_filler["x-airtelwork-webversion"]}' -e 'conf_file=/etc/nginx/conf.d/default.conf'")
                              if (lob == "AirtelWork") {
                                 notifytelegram("%31%E2%83%A3 %30%E2%83%A3","100","100% deployment complete!!","${project}","${gitTag}","Production")               
                                 DB_update("${project}", "20%", "${gitTag}","success","false","${env.BUILD_NUMBER}","completed","${lob}","prod")
                              }
                          }
                          gitTagInfo= "${JIRA_ID} ${env.BUILD_URL}[${REGISTRY_URL}/${imageTag}]"
                          sh("git tag ${gitTag} -m '${gitTagInfo}'")
                          sshagent(['ansible_ssh']) {
                            sh("git push origin ${gitTag}")
                            url = "https://code.airtelworld.in:7990/bitbucket/projects/${git_proj_key.toLowerCase()}/repos/${project}"
                            sh("chmod 777 sandbox/services/changelog.sh && ./sandbox/services/changelog.sh '$url'> /tmp/CHANGELOG.md")
                            sh("sed -i '1,2d' /tmp/CHANGELOG.md")
                            JENKINS_URL = sh returnStdout: true, script:"cat /tmp/CHANGELOG.md | grep -E 'URL=' | cut -d '=' -f 2 | head -1"
                            JENKINS_URL = JENKINS_URL.trim()
                            sh("sed -i 's#__URL__#${JENKINS_URL}#' ./sandbox/config/DeploymentReport.html")
                            sh("sed -i 's/URL.*//' /tmp/CHANGELOG.md")
                            sh("sed -e '/__changelog__/{' -e 'r /tmp/CHANGELOG.md' -e 'd' -e '}' -i ./sandbox/config/DeploymentReport.html")
                            sendMail(platforms_map,project,gitTag,"SUCCESS","${JIRA_ID}")
                          }

                      }
                      catch(err)
                      {
                          sendMail(platforms_map,project,gitTag,"FAILURE","${JIRA_ID}")
                          //notify("Application Deployment","FAILED","${project}","PRODUCTION",platforms_map.teams_webhook)
                          throw err
                      }
//                       sendMail(platforms_map,project,gitTag,"FAILURE","${JIRA_ID}")
                     // notify("Application Deployment","SUCCESS","${project}","PRODUCTION",platforms_map.teams_webhook)
              }
              break;
        }

    }
    try{sh ("docker rmi ${imageTag}")} catch (err) {}
  }
}
return this;
// ========================================================================================================================
// [START] MAIN FUNCTION
// ========================================================================================================================
