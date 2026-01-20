package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.math.BigDecimal; // ¡CORREGIDO: Importación de BigDecimal!
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID; // ¡CORREGIDO: Importación de UUID!

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.models.ListItemCollectionResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

/**
 * Servlet para la gestión administrativa de tiendas.
 * Maneja el listado, guardado, refresco individual y sincronización global con SharePoint.
 */
@WebServlet("/admin-tiendas")
public class AdminTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(AdminTiendasServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    // Usuario con permisos especiales (SuperAdmin)
    private static final String SUPER_USER_ID = "28654139A";

    public static class Tienda {
        public int codigo;
        public String denominacion;
        public String direccion;
        public String lat;
        public String lon;
        public String cp;
        public String poblacion;
        public String cadena;
        public String disponible;
        public String prioridad; // Cambiado a String para mantener formato 0001
        public int huecosTurno1;
        public int huecosTurno2;
        public int huecosTurno3;
        public int huecosTurno4;
        public String sqlRowUUID; 
        public String supervisor;
        public String coordinador; // Añadido
        public String marca;
        public String codZona;
        public String zona;
        public String modalidad;
    }

    private boolean isAdmin(HttpSession session) {
        if (session == null || session.getAttribute("usuario") == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || "S".equals(isAdminAttr);
    }

    private boolean isSuperAdmin(HttpSession session) {
        if (session == null) return false;
        String usuario = (String) session.getAttribute("usuario");
        if (usuario == null) return false;
        return SUPER_USER_ID.equals(usuario.trim().toUpperCase());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        HttpSession session = request.getSession(false);
        if (!isAdmin(session)) { response.setStatus(HttpServletResponse.SC_FORBIDDEN); return; }

        String supervisorParam = request.getParameter("supervisor");
        String zonaParam = request.getParameter("zona");
        List<Tienda> tiendas = new ArrayList<>();
        
        StringBuilder sql = new StringBuilder("SELECT codigo, denominacion, Direccion, Lat, Lon, cp, Poblacion, Cadena, disponible, prioridad, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, SqlRowUUID, Supervisor, Coordinador, Marca, CodZona, Zona, Modalidad FROM tiendas WHERE 1=1");
        
        if (supervisorParam != null && !supervisorParam.isEmpty()) sql.append(" AND Supervisor = ?");
        if (zonaParam != null && !zonaParam.isEmpty()) sql.append(" AND Zona = ?");
        sql.append(" ORDER BY denominacion");

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (supervisorParam != null && !supervisorParam.isEmpty()) stmt.setString(idx++, supervisorParam);
            if (zonaParam != null && !zonaParam.isEmpty()) stmt.setString(idx++, zonaParam);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Tienda t = new Tienda();
                    t.codigo = rs.getInt("codigo");
                    t.denominacion = rs.getString("denominacion");
                    t.direccion = rs.getString("Direccion");
                    t.lat = rs.getString("Lat"); 
                    t.lon = rs.getString("Lon");
                    t.cp = rs.getString("cp"); 
                    t.poblacion = rs.getString("Poblacion");
                    t.cadena = rs.getString("Cadena"); 
                    t.disponible = rs.getString("disponible");
                    t.prioridad = rs.getString("prioridad"); // Leer como String
                    t.huecosTurno1 = rs.getInt("HuecosTurno1"); 
                    t.huecosTurno2 = rs.getInt("HuecosTurno2");
                    t.huecosTurno3 = rs.getInt("HuecosTurno3"); 
                    t.huecosTurno4 = rs.getInt("HuecosTurno4");
                    t.sqlRowUUID = rs.getString("SqlRowUUID");
                    t.supervisor = rs.getString("Supervisor");
                    t.coordinador = rs.getString("Coordinador"); // Mapeo del coordinador
                    t.marca = rs.getString("Marca");
                    t.codZona = rs.getString("CodZona");
                    t.zona = rs.getString("Zona");
                    t.modalidad = rs.getString("Modalidad");
                    tiendas.add(t);
                }
            }
            mapper.writeValue(response.getWriter(), tiendas);
        } catch (SQLException e) { 
            logger.error("Error al listar tiendas", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); 
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);
        
        if (!isAdmin(session)) { 
            jsonResponse.put("success", false); 
            jsonResponse.put("message", "Acceso denegado.");
            mapper.writeValue(response.getWriter(), jsonResponse); 
            return; 
        }
        
        String adminUser = (String) session.getAttribute("usuario");
        String action = request.getParameter("action");

        try {
            if ("refresh".equals(action)) {
                handleRefreshIndividual(request, jsonResponse, adminUser);
            } else if ("refreshAll".equals(action)) {
                if (!isSuperAdmin(session)) {
                    jsonResponse.put("success", false);
                    jsonResponse.put("message", "Acción restringida al Super Administrador.");
                } else {
                    handleRefreshAll(jsonResponse, adminUser);
                }
            } else if ("save".equals(action)) {
                handleSave(request, jsonResponse, session, adminUser);
            } else {
                jsonResponse.put("success", false);
                jsonResponse.put("message", "Acción no reconocida.");
            }
        } catch (Exception e) {
            logger.error("Error procesando acción: " + action, e);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error interno: " + e.getMessage());
        }

        mapper.writeValue(response.getWriter(), jsonResponse);
    }

    private void handleRefreshAll(Map<String, Object> jsonResponse, String adminUser) throws Exception {
        String listId = SharePointUtil.getListId(SharePointUtil.SITE_ID, SharePointUtil.LIST_NAME_TIENDAS);
        if (listId == null) {
            jsonResponse.put("success", false);
            jsonResponse.put("message", "No se encontró la lista 'Tiendas' en SharePoint.");
            return;
        }

        ListItemCollectionResponse spResponse = SharePointUtil.getListItems(SharePointUtil.SITE_ID, listId);
        if (spResponse == null || spResponse.getValue() == null) {
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error al recuperar datos de SharePoint.");
            return;
        }

        Set<Integer> codesFromSP = new HashSet<>();
        int creadas = 0;
        int actualizadas = 0;
        int desactivadas = 0;

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);

            for (ListItem item : spResponse.getValue()) {
                Map<String, Object> fields = item.getFields().getAdditionalData();
                Object codigoObj = fields.get("codigo");
                if (codigoObj == null) continue;

                int codigo = parseToInt(codigoObj);
                codesFromSP.add(codigo);

                boolean exists = false;
                try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM tiendas WHERE codigo = ?")) {
                    check.setInt(1, codigo);
                    try (ResultSet rs = check.executeQuery()) { exists = rs.next(); }
                }

                String sql;
                if (exists) {
                    sql = "UPDATE tiendas SET denominacion=?, Direccion=?, cp=?, Poblacion=?, Cadena=?, disponible=?, Lat=?, Lon=?, prioridad=?, HuecosTurno1=?, HuecosTurno2=?, HuecosTurno3=?, HuecosTurno4=?, SqlRowUUID=?, Supervisor=?, Coordinador=?, Marca=?, CodZona=?, Zona=?, Modalidad=?, notificar='S' WHERE codigo=?";
                    actualizadas++;
                } else {
                    sql = "INSERT INTO tiendas (denominacion, Direccion, cp, Poblacion, Cadena, disponible, Lat, Lon, prioridad, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, SqlRowUUID, Supervisor, Coordinador, Marca, CodZona, Zona, Modalidad, notificar, codigo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'S', ?)";
                    creadas++;
                }

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, String.valueOf(fields.getOrDefault("denominacion", "")));
                    stmt.setString(2, String.valueOf(fields.getOrDefault("direccion", "")));
                    stmt.setString(3, String.valueOf(fields.getOrDefault("cp", "")));
                    stmt.setString(4, String.valueOf(fields.getOrDefault("poblacion", "")));
                    stmt.setString(5, String.valueOf(fields.getOrDefault("cadena", "")));
                    Object disp = fields.get("disponible");
                    stmt.setString(6, (disp instanceof Boolean && (Boolean)disp) ? "S" : "N");
                    
                    // CORRECCIÓN DECIMAL COMA POR PUNTO
                    stmt.setString(7, String.valueOf(fields.getOrDefault("lat", "0")).replace(',', '.'));
                    stmt.setString(8, String.valueOf(fields.getOrDefault("lon", "0")).replace(',', '.'));
                    
                    // PRIORIDAD CON FORMATO 0001
                    stmt.setString(9, String.format("%04d", parseToInt(fields.get("prioridad"))));
                    
                    stmt.setInt(10, parseToInt(fields.get("huecosTurno1")));
                    stmt.setInt(11, parseToInt(fields.get("huecosTurno2")));
                    stmt.setInt(12, parseToInt(fields.get("huecosTurno3")));
                    stmt.setInt(13, parseToInt(fields.get("huecosTurno4")));
                    stmt.setString(14, String.valueOf(fields.getOrDefault("SqlRowUUID", "")));
                    stmt.setString(15, String.valueOf(fields.getOrDefault("SUPERVISOR", ""))); // Mayúsculas
                    stmt.setString(16, String.valueOf(fields.getOrDefault("Coordinador", ""))); // Mapeo de Coordinador
                    
                    // CAMPOS OBLIGATORIOS FALTANTES
                    stmt.setString(17, String.valueOf(fields.getOrDefault("Marca", "")));
                    stmt.setString(18, String.valueOf(fields.getOrDefault("CodZona", "")));
                    stmt.setString(19, String.valueOf(fields.getOrDefault("Zona", "")));
                    stmt.setString(20, String.valueOf(fields.getOrDefault("Modalidad", "")));
                    
                    stmt.setInt(21, codigo); // Último parámetro: codigo para la cláusula WHERE o INSERT
                    stmt.executeUpdate();
                }
            }

            // Desactivar las locales no en SP
            List<Integer> allLocalCodes = new ArrayList<>();
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT codigo FROM tiendas")) {
                while(rs.next()) allLocalCodes.add(rs.getInt(1));
            }
            
            for(Integer localCode : allLocalCodes) {
                if(!codesFromSP.contains(localCode)) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE tiendas SET disponible='N', notificar='S' WHERE codigo=?")) {
                        ps.setInt(1, localCode);
                        ps.executeUpdate();
                        desactivadas++;
                    }
                }
            }

            String logMsg = String.format("Sincronización masiva de tiendas desde SP finalizada. Creadas: %d, Actualizadas: %d, Desactivadas: %d", creadas, actualizadas, desactivadas);
            LogUtil.logOperation(conn, "SYNC_ALL_T", adminUser, logMsg);

            conn.commit();
            
            jsonResponse.put("success", true);
            jsonResponse.put("message", String.format("Sincronización completada: %d nuevas, %d actualizadas y %d desactivadas.", creadas, actualizadas, desactivadas));
        }
    }

    private void handleRefreshIndividual(HttpServletRequest request, Map<String, Object> jsonResponse, String adminUser) throws Exception {
        String codigo = request.getParameter("codigo");
        
        try (Connection conn = DatabaseUtil.getConnection()) { // Obtener conexión para logging en SharePointUtil
            Map<String, Object> spData = SharePointUtil.getTiendaFromSP(conn, codigo); // ¡CORREGIDO: Pasar la conexión!
            
            if (spData == null) {
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE tiendas SET disponible='N', notificar='S' WHERE codigo=?")) {
                    stmt.setInt(1, Integer.parseInt(codigo));
                    stmt.executeUpdate();
                }
                LogUtil.logOperation(conn, "REFRESH_T_MISSING", adminUser, "Tienda " + codigo + " no encontrada en SharePoint. Marcada como 'No disponible'.");
                jsonResponse.put("success", true);
                jsonResponse.put("message", "Tienda marcada como no disponible (no hallada en SP).");
                return;
            }

            String sql = "UPDATE tiendas SET denominacion=?, Direccion=?, cp=?, Poblacion=?, Cadena=?, disponible=?, Lat=?, Lon=?, prioridad=?, HuecosTurno1=?, HuecosTurno2=?, HuecosTurno3=?, HuecosTurno4=?, SqlRowUUID=?, Supervisor=?, Coordinador=?, Marca=?, CodZona=?, Zona=?, Modalidad=?, notificar='S' WHERE codigo=?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, String.valueOf(spData.getOrDefault("denominacion", "")));
                stmt.setString(2, String.valueOf(spData.getOrDefault("direccion", "")));
                stmt.setString(3, String.valueOf(spData.getOrDefault("cp", "")));
                stmt.setString(4, String.valueOf(spData.getOrDefault("poblacion", "")));
                stmt.setString(5, String.valueOf(spData.getOrDefault("cadena", "")));
                Object disp = spData.get("disponible");
                stmt.setString(6, (disp instanceof Boolean && (Boolean)disp) ? "S" : "N");
                stmt.setString(7, String.valueOf(spData.getOrDefault("lat", "0")).replace(',', '.'));
                stmt.setString(8, String.valueOf(spData.getOrDefault("lon", "0")).replace(',', '.'));
                stmt.setString(9, String.format("%04d", parseToInt(spData.get("prioridad"))));
                stmt.setInt(10, parseToInt(spData.get("huecosTurno1")));
                stmt.setInt(11, parseToInt(spData.get("huecosTurno2")));
                stmt.setInt(12, parseToInt(spData.get("huecosTurno3")));
                stmt.setInt(13, parseToInt(spData.get("huecosTurno4")));
                stmt.setString(14, String.valueOf(spData.getOrDefault("SqlRowUUID", "")));
                stmt.setString(15, String.valueOf(spData.getOrDefault("SUPERVISOR", "")));
                stmt.setString(16, String.valueOf(spData.getOrDefault("Coordinador", "")));
                stmt.setString(17, String.valueOf(spData.getOrDefault("Marca", "")));
                stmt.setString(18, String.valueOf(spData.getOrDefault("CodZona", "")));
                stmt.setString(19, String.valueOf(spData.getOrDefault("Zona", "")));
                stmt.setString(20, String.valueOf(spData.getOrDefault("Modalidad", "")));
                stmt.setInt(21, Integer.parseInt(codigo));
                stmt.executeUpdate();
            }
            LogUtil.logOperation(conn, "REFRESH_T", adminUser, "Refrescada tienda: " + codigo);
            jsonResponse.put("success", true);
            jsonResponse.put("message", "Tienda actualizada.");
        }
    }

    private void handleSave(HttpServletRequest request, Map<String, Object> jsonResponse, HttpSession session, String adminUser) throws SQLException {
        boolean isUpdate = Boolean.parseBoolean(request.getParameter("isUpdate"));
        if (!isUpdate && !isSuperAdmin(session)) {
            jsonResponse.put("success", false); jsonResponse.put("message", "Permisos insuficientes.");
            return;
        }

        int codigo = Integer.parseInt(request.getParameter("codigo"));
        
        // Se han añadido valores por defecto para los campos NOT NULL que no son editables en esta vista
        String sql = isUpdate 
            ? "UPDATE tiendas SET disponible=?, HuecosTurno1=?, HuecosTurno2=?, HuecosTurno3=?, HuecosTurno4=?, notificar='S' WHERE codigo=?"
            : "INSERT INTO tiendas (disponible, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, notificar, codigo, denominacion, Direccion, Lat, Lon, cp, Poblacion, Cadena, prioridad, SqlRowUUID, Supervisor, Coordinador, Marca, CodZona, Zona, Modalidad) VALUES (?, ?, ?, ?, ?, 'S', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, request.getParameter("disponible"));
            stmt.setInt(2, Integer.parseInt(request.getParameter("huecosTurno1")));
            stmt.setInt(3, Integer.parseInt(request.getParameter("huecosTurno2")));
            stmt.setInt(4, Integer.parseInt(request.getParameter("huecosTurno3")));
            stmt.setInt(5, Integer.parseInt(request.getParameter("huecosTurno4")));
            stmt.setInt(6, codigo);

            if (!isUpdate) {
                // Valores por defecto para INSERT de campos NOT NULL
                stmt.setString(7, request.getParameter("denominacion")); // Asumiendo que denominacion se pasa
                stmt.setString(8, request.getParameter("direccion")); // Asumiendo que direccion se pasa
                // Los siguientes son campos con valor por defecto o que se rellenan al crear.
                // Si SharePoint tiene valores por defecto para estos campos, se podrían buscar allí.
                // Por ahora, se usan valores por defecto o vacíos para evitar 'cannot be null'.
                stmt.setBigDecimal(9, new BigDecimal(request.getParameter("lat").replace(',', '.'))); // Lat con corrección de coma
                stmt.setBigDecimal(10, new BigDecimal(request.getParameter("lon").replace(',', '.'))); // Lon con corrección de coma
                stmt.setString(11, request.getParameter("cp")); // CP
                stmt.setString(12, request.getParameter("poblacion")); // Poblacion
                stmt.setString(13, request.getParameter("cadena")); // Cadena
                stmt.setString(14, String.format("%04d", parseToInt(request.getParameter("prioridad")))); // Prioridad
                stmt.setString(15, UUID.randomUUID().toString()); // SqlRowUUID nuevo
                stmt.setString(16, request.getParameter("supervisor")); // Supervisor
                stmt.setString(17, request.getParameter("coordinador")); // Coordinador
                stmt.setString(18, request.getParameter("marca")); // Marca
                stmt.setString(19, request.getParameter("codZona")); // CodZona
                stmt.setString(20, request.getParameter("zona")); // Zona
                stmt.setString(21, request.getParameter("modalidad")); // Modalidad
            }
            
            stmt.executeUpdate();
            jsonResponse.put("success", true);
            LogUtil.logOperation(conn, isUpdate ? "SAVE_T" : "CREATE_T", adminUser, "Guardada tienda: " + codigo);
        }
    }

    private int parseToInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.parseInt(String.valueOf(obj).trim()); } catch (Exception e) { return 0; }
    }
}
