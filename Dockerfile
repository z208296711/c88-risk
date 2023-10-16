FROM openjdk:11
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
ENTRYPOINT ["sh", "-c", "java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=9999 ${JAVA_OPTS} -Dspring.profiles.active=${JAVA_ENV} -jar /app.jar ${0} ${@}"]
