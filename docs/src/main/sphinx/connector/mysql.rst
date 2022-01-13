===============
MySQL connector
===============

The MySQL connector allows querying and creating tables in an external
`MySQL <https://www.mysql.com/>`_ instance. This can be used to join data between different
systems like MySQL and Hive, or between two different MySQL instances.

Requirements
------------

To connect to MySQL, you need:

* MySQL 5.7, 8.0 or higher.
* Network access from the Trino coordinator and workers to MySQL.
  Port 3306 is the default port.

Configuration
-------------

To configure the MySQL connector, create a catalog properties file
in ``etc/catalog`` named, for example, ``mysql.properties``, to
mount the MySQL connector as the ``mysql`` catalog.
Create the file with the following contents, replacing the
connection properties as appropriate for your setup:

.. code-block:: text

    connector.name=mysql
    connection-url=jdbc:mysql://example.net:3306
    connection-user=root
    connection-password=secret

The ``connection-url`` defines the connection information and parameters to pass
to the MySQL JDBC driver. The supported parameters for the URL are
available in the `MySQL Developer Guide
<https://dev.mysql.com/doc/connector-j/8.0/en/>`_.

For example, the following ``connection-url`` allows you to
configure the JDBC driver to interpret time values based on UTC as a timezone on
the server, and serves as a `workaround for a known issue
<https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-usagenotes-known-issues-limitations.html>`_.

.. code-block:: text

    connection-url=jdbc:mysql://example.net:3306?serverTimezone=UTC

The ``connection-user`` and ``connection-password`` are typically required and
determine the user credentials for the connection, often a service user. You can
use :doc:`secrets </security/secrets>` to avoid actual values in the catalog
properties files.

Multiple MySQL servers
^^^^^^^^^^^^^^^^^^^^^^

You can have as many catalogs as you need, so if you have additional
MySQL servers, simply add another properties file to ``etc/catalog``
with a different name, making sure it ends in ``.properties``. For
example, if you name the property file ``sales.properties``, Trino
creates a catalog named ``sales`` using the configured connector.

.. include:: jdbc-common-configurations.fragment

.. include:: jdbc-procedures.fragment

.. include:: jdbc-case-insensitive-matching.fragment

.. include:: non-transactional-insert.fragment

.. _mysql-type-mapping:

Type mapping
------------

Because Trino and MySQL each support types that the other does not, this
connector modifies some types when reading or writing data.

MySQL to Trino read type mapping
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This connector supports reading the following MySQL types and performs
conversion to Trino types with the detailed mappings as shown in the following
table.

.. list-table:: MySQL to Trino type mapping
  :widths: 30, 20, 50
  :header-rows: 1

  * - MySQL database type
    - Trino type
    - Notes
  * - ``BIT``
    - ``BOOLEAN``
    -
  * - ``BOOLEAN``
    - ``TINYINT``
    -
  * - ``TINYINT``
    - ``TINYINT``
    -
  * - ``SMALLINT``
    - ``SMALLINT``
    -
  * - ``INTEGER``
    - ``INTEGER``
    -
  * - ``BIGINT``
    - ``BIGINT``
    -
  * - ``DOUBLE PRECISION``
    - ``DOUBLE``
    -
  * - ``FLOAT``
    - ``REAL``
    -
  * - ``REAL``
    - ``REAL``
    -
  * - ``DECIMAL(p, s)``
    - ``DECIMAL(p, s)``
    - See :ref:`MySQL DECIMAL type handling <mysql-decimal-handling>`
  * - ``CHAR(n)``
    - ``CHAR(n)``
    -
  * - ``VARCHAR(n)``
    - ``VARCHAR(n)``
    -
  * - ``TINYTEXT``
    - ``VARCHAR(255)``
    -
  * - ``TEXT``
    - ``VARCHAR(65535)``
    -
  * - ``MEDIUMTEXT``
    - ``VARCHAR(16777215)``
    -
  * - ``LONGTEXT``
    - ``VARCHAR``
    -
  * - ``BINARY``, ``VARBINARY``, ``TINYBLOB``, ``BLOB``, ``MEDIUMBLOB``, ``LONGBLOB``
    - ``VARBINARY``
    -
  * - ``DATE``
    - ``DATE``
    -
  * - ``TIME(n)``
    - ``TIME(n)``
    -
  * - ``DATETIME(n)``
    - ``DATETIME(n)``
    -
  * - ``TIMESTAMP(n)``
    - ``TIMESTAMP(n)``
    -

No other types are supported.

Trino to MySQL write type mapping
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This connector supports writing the following Trino types and performs
conversion to MySQL types with the detailed mappings as shown in the
following table.

.. list-table:: Trino to MySQL type mapping
  :widths: 30, 20, 50
  :header-rows: 1

  * - Trino type
    - MySQL type
    - Notes
  * - ``BOOLEAN``
    - ``TINYINT``
    -
  * - ``TINYINT``
    - ``TINYINT``
    -
  * - ``SMALLINT``
    - ``SMALLINT``
    -
  * - ``INTEGER``
    - ``INTEGER``
    -
  * - ``BIGINT``
    - ``BIGINT``
    -
  * - ``REAL``
    - ``REAL``
    -
  * - ``DOUBLE``
    - ``DOUBLE PRECISION``
    -
  * - ``DECIMAL(p, s)``
    - ``DECIMAL(p, s)``
    - :ref:`MySQL DECIMAL type handling <mysql-decimal-handling>`
  * - ``CHAR(n)``
    - ``CHAR(n)``
    -
  * - ``VARCHAR(n)``
    - ``VARCHAR(n)``
    -
  * - ``DATE``
    - ``DATE``
    -
  * - ``TIME(n)``
    - ``TIME(n)``
    -
  * - ``TIMESTAMP(n)``
    - ``TIMESTAMP(n)``
    -

No other types are supported.

.. _mysql-decimal-handling:

Decimal type handling
^^^^^^^^^^^^^^^^^^^^^

``DECIMAL`` types with precision larger than 38 can be mapped to a Trino ``DECIMAL``
by setting the ``decimal-mapping`` configuration property or the ``decimal_mapping`` session property to
``allow_overflow``. The scale of the resulting type is controlled via the ``decimal-default-scale``
configuration property or the ``decimal-rounding-mode`` session property. The precision is always 38.

By default, values that require rounding or truncation to fit will cause a failure at runtime. This behavior
is controlled via the ``decimal-rounding-mode`` configuration property or the ``decimal_rounding_mode`` session
property, which can be set to ``UNNECESSARY`` (the default),
``UP``, ``DOWN``, ``CEILING``, ``FLOOR``, ``HALF_UP``, ``HALF_DOWN``, or ``HALF_EVEN``
(see `RoundingMode <https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/math/RoundingMode.html#enum.constant.summary>`_).

.. include:: jdbc-type-mapping.fragment

Querying MySQL
--------------

The MySQL connector provides a schema for every MySQL *database*.
You can see the available MySQL databases by running ``SHOW SCHEMAS``::

    SHOW SCHEMAS FROM mysql;

If you have a MySQL database named ``web``, you can view the tables
in this database by running ``SHOW TABLES``::

    SHOW TABLES FROM mysql.web;

You can see a list of the columns in the ``clicks`` table in the ``web`` database
using either of the following::

    DESCRIBE mysql.web.clicks;
    SHOW COLUMNS FROM mysql.web.clicks;

Finally, you can access the ``clicks`` table in the ``web`` database::

    SELECT * FROM mysql.web.clicks;

If you used a different name for your catalog properties file, use
that catalog name instead of ``mysql`` in the above examples.

.. _mysql-pushdown:

Pushdown
--------

The connector supports pushdown for a number of operations:

* :ref:`join-pushdown`
* :ref:`limit-pushdown`
* :ref:`topn-pushdown`

:ref:`Aggregate pushdown <aggregation-pushdown>` for the following functions:

* :func:`avg`
* :func:`count`
* :func:`max`
* :func:`min`
* :func:`sum`
* :func:`stddev`
* :func:`stddev_pop`
* :func:`stddev_samp`
* :func:`variance`
* :func:`var_pop`
* :func:`var_samp`

.. include:: no-pushdown-text-type.fragment

.. _mysql-sql-support:

SQL support
-----------

The connector provides read access and write access to data and metadata in the
MySQL database. In addition to the :ref:`globally available <sql-globally-available>` and
:ref:`read operation <sql-read-operations>` statements, the connector supports
the following statements:

* :doc:`/sql/insert`
* :doc:`/sql/delete`
* :doc:`/sql/truncate`
* :doc:`/sql/create-table`
* :doc:`/sql/create-table-as`
* :doc:`/sql/drop-table`
* :doc:`/sql/create-schema`
* :doc:`/sql/drop-schema`

.. include:: sql-delete-limitation.fragment
