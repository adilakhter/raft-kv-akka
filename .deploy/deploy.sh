#!/usr/bin/env bash
chmod 600 .deploy/deploy.pem
scp -o StrictHostKeyChecking=no -i .deploy/deploy.pem target/scala-2.12/raft.jar ubuntu@52.214.224.105:/home/ubuntu/
ssh -o StrictHostKeyChecking=no -i .deploy/deploy.pem ubuntu@52.214.224.105 "killall -9 java; nohup java -jar /home/ubuntu/raft.jar `</dev/null` >app1.log 2>&1 &"
ssh -o StrictHostKeyChecking=no -i .deploy/deploy.pem ubuntu@52.214.224.105 "nohup java -Dclustering.port=2552 -jar /home/ubuntu/raft.jar worker`</dev/null` >app2.log 2>&1 &"
ssh -o StrictHostKeyChecking=no -i .deploy/deploy.pem ubuntu@52.214.224.105 "nohup java -Dclustering.port=2553 -jar /home/ubuntu/raft.jar worker`</dev/null` >app3.log 2>&1 &"
ssh -o StrictHostKeyChecking=no -i .deploy/deploy.pem ubuntu@52.214.224.105 "nohup java -Dclustering.port=2554 -jar /home/ubuntu/raft.jar worker`</dev/null` >app4.log 2>&1 &"
ssh -o StrictHostKeyChecking=no -i .deploy/deploy.pem ubuntu@52.214.224.105 "nohup java -Dclustering.port=2555 -jar /home/ubuntu/raft.jar worker`</dev/null` >app5.log 2>&1 &"