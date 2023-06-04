### OpenShift build and deploy
```
export NS=gitbucket
oc create ns $NS
oc -n $NS new-build --name gitbucket https://github.com/dsevost/gitbucket#ds02 --strategy=docker
oc -n $NS new-app postgresql-persistent --name postgresql -p POSTGRESQL_DATABASE=gitbucket -p POSTGRESQL_VERSION=13-el7 -p VOLUME_CAPACITY=2Gi
oc -n $NS logs -f bc/gitbucket
oc -n $NS new-app --name gitbucket gitbucket -e GITBUCKET_DB_URL=jdbc:postgresql://postgresql:5432/gitbucket -p GITBUCKET_PORT=8080 -o json --dry-run | \
    jq '.items[] | select(.kind == "Deployment") | .spec.template.spec.containers[0].env += [{"name": "GITBUCKET_DB_PASSWORD", "valueFrom": { "secretKeyRef": { "name": "postgresql", "key": "database-password" }}},{"name": "GITBUCKET_DB_USER", "valueFrom": { "secretKeyRef": { "name": "postgresql", "key": "database-user" }}}]' | \
    oc -n $NS apply -f -
oc -n $NS set volume deploy/gitbucket --add --claim-name gitbucket --claim-size=24Gi -m /deployments/data -t pvc
oc -n $NS exec $(oc -n $NS get pods -l deployment=gitbucket -o name) -- \
    sed -ci.bak1 "s/.*connectionTimeout.*/  connectionTimeout = 60000/ ; s/.*maximumPoolSize.*/  maximumPoolSize = 50/" /deployments/data/database.conf
oc -n $NS delete $(oc -n $NS get pods -l deployment=gitbucket -o name)
sleep 10
oc -n $NS exec $(oc -n $NS get pods -l deployment=gitbucket -o name) -- cat /deployments/data/database.conf
oc -n $NS expose deploy/gitbucket --port 8080
oc -n $NS create route edge --service gitbucket \
    --hostname=gitbucket.$(oc -n openshift-ingress-operator get ingresscontrollers.operator.openshift.io default -o jsonpath='{.status.domain}')
```