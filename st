On passe à l’atelier Spring Boot → PostgreSQL → Flyway. J’ai vérifié que le conteneur est healthy sur 127.0.0.1:5433. Ton objectif est de voir Spring Boot se connecter et Flyway créer la table requests au démarrage.
1. Ajoute quatre dépendances dans le pom.xml de serviceops-java. spring-boot-starter-data-jpa prépare la connexion et l’atelier de mapping prévu au programme. spring-boot-starter-flyway déclenche les migrations au démarrage. flyway-database-postgresql donne à Flyway le support de PostgreSQL. org.postgresql:postgresql est le pilote qui permet à Java de communiquer avec la base. Le parent Spring Boot gère leurs versions. JPA et connexion SQL · Flyway dans Spring Boot
2. Recharge Maven, puis ouvre Run → Edit Configurations → Environment variables dans IntelliJ. Renseigne les trois valeurs dans ta configuration locale. Configuration IntelliJ
   Variable	Valeur
   SPRING_DATASOURCE_URL	jdbc:postgresql://127.0.0.1:5433/serviceops
   SPRING_DATASOURCE_USERNAME	serviceops
   SPRING_DATASOURCE_PASSWORD	La valeur de POSTGRES_PASSWORD dans ton .env local
3. Lance l’application et cherche Flyway dans la console. La preuve attendue est une entrée V1 réussie dans flyway_schema_history et la présence de la table requests. Le fichier SQL est déjà à l’emplacement que Flyway lit par défaut. [Migration `V1`](/Users/macbookpro/technical-interventions/backends/java/serviceops-java/src/main/resources/db/migration/V1__create_requests.sql)
Fais ce premier démarrage et montre-moi les lignes Flyway. Une fois la migration confirmée, on branchera save et findById sur PostgreSQL.