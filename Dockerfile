FROM fedora:37 AS BUILDER

RUN \
    curl \
	-SL https://www.scala-sbt.org/sbt-rpm.repo \
	-o /etc/yum.repos.d/sbt-rpm.repo \
    && \
    dnf install -y \
	--disablerepo \* \
	--enablerepo fedora,updates,sbt-rpm \
	git \
	java-17-openjdk-devel \
	java-17-openjdk-headless \
	sbt \
    && \
    dnf clean all

USER 1000

ENV \
    HOME=/home/1000

WORKDIR $HOME/gitbucket

COPY --chown=1000 . .

RUN \
    pwd && \
    sbt executable && \
    rename -v -- '.war' '-fat.jar' $HOME/gitbucket/target/executable/*.war

FROM registry.access.redhat.com/ubi8/openjdk-17-runtime:latest AS RUNTIME

USER 0

RUN \
    microdnf install -y \
	fontconfig \
	git

COPY --from=BUILDER /home/1000/gitbucket/target/executable/*.jar /deployments/

ENV \
    GITBUCKET_HOME=/deployments/data
