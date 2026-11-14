-- Selecciona una base de pruebas antes de ejecutar.
-- F10 avanza por instruccion; F9 fija un punto. Doble clic en v_filas abre su tabla.
DELIMITER $$

CREATE PROCEDURE `sp_debug_variables`()
BEGIN
    DECLARE v_base DECIMAL(12,2) DEFAULT NULL;
    DECLARE v_copia DECIMAL(12,2) DEFAULT NULL;
    DECLARE v_total DECIMAL(12,2) DEFAULT NULL;
    DECLARE v_filas JSON DEFAULT NULL;
    -- F10: ejecutar el SELECT. En la siguiente pausa v_base vale 125.50.
    SELECT importe INTO v_base
    FROM (SELECT CAST(125.50 AS DECIMAL(12,2)) AS importe) AS prueba;
    -- Punto de quiebre aquí: pasa el cursor sobre v_base y v_copia.
    SET v_copia = v_base;
    SET v_total = v_copia * 2;
    -- Tabla temporal de esta sesión. El SELECT se visualizará como cuadrícula.
    CREATE TEMPORARY TABLE `nx_ejemplo_1x6u4jb` (
        id INT, cliente VARCHAR(60), importe DECIMAL(12,2)
    );
    INSERT INTO `nx_ejemplo_1x6u4jb` VALUES (1, 'Ana', v_base), (2, 'Luis', v_total);
    SELECT JSON_ARRAYAGG(JSON_OBJECT('id',id,'cliente',cliente,'importe',importe))
        INTO v_filas FROM `nx_ejemplo_1x6u4jb`;
    -- Doble clic en v_filas: abrir el visualizador tabular de la variable JSON.
    SELECT id, cliente, importe FROM `nx_ejemplo_1x6u4jb` ORDER BY id;
    -- Pausa aquí para revisar el resultado del SELECT sin terminar el SP.
    SET v_total = v_total + 10;
    SELECT v_base AS base, v_copia AS copia, v_total AS total;
    DROP TEMPORARY TABLE `nx_ejemplo_1x6u4jb`;
END
$$

DELIMITER ;
