#!/usr/bin/env bash
chmod 600 .deploy/deploy.pem
scp -o StrictHostKeyChecking=no -i .deploy/deploy.pem target/raft.jar ec2-user@52.214.224.105:/home/ec2-user/
ssh -o StrictHostKeyChecking=no -i .deploy/deploy.pem ec2-user@52.214.224.105 "killall -9 java; nohup java -jar /home/ec2-user/raft.jar `</dev/null` >app1.log 2>&1 &"
ssh -o StrictHostKeyChecking=no -i .deploy/deploy.pem ec2-user@52.214.224.105 "killall -9 java; nohup java -jar /home/ec2-user/raft.jar -Dclustering.port=2552 worker`</dev/null` >app2.log 2>&1 &"