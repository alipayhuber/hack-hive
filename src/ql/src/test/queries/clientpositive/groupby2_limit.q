set mapred.reduce.tasks=31;

EXPLAIN
SELECT src.key, sum(substr(src.value,5)) FROM src GROUP BY src.key ORDER BY key LIMIT 5;

SELECT src.key, sum(substr(src.value,5)) FROM src GROUP BY src.key ORDER BY key LIMIT 5;

