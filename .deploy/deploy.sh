#!/usr/bin/env bash
chmod 600 .deploy/deploy.pem
scp -o StrictHostKeyChecking=no -i .deploy/deploy.pem target/raft.jar ubuntu@52.214.224.105:/home/ubuntu/
ssh -o StrictHostKeyChecking=no -i .deploy/deploy.pem ubuntu@52.214.224.105 "killall -9 java; nohup java -jar /home/ubuntu/raft.jar `</dev/null` >app1.log 2>&1 &"
ssh -o StrictHostKeyChecking=no -i .deploy/deploy.pem ubuntu@52.214.224.105 "killall -9 java; nohup java -jar /home/ubuntu/raft.jar -Dclustering.port=2552 worker`</dev/null` >app2.log 2>&1 &"