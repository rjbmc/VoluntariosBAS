package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

@WebServlet("/admin-tiendas")
public class AdminTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    private static final Logger logger = LoggerFactory.getLogger(AdminTiendasServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public static class Tienda {
        public int codigo;
        public int prioridad;
        public int huecosTurno1;
        public int huecosTurno2;
        public int huecosTurno3;
        public int huecosTurno4;
        public String denominacion;
        public String direccion;
        public String lat;
        public String lon;
        public String cp;
        public String poblacion;
        public String cadena;
        public String disponible;
    }

    // --- CORRECCIÓN: Método de seguridad robusto (acepta Boolean true o String "S") ---
    private boolean isAdmin(HttpSession session) {
        if (session == null || session.getAttribute("usuario") == null) return false;
        
        Object isAdminAttr = session.getAttribute("isAdmin");
        if (isAdminAttr instanceof Boolean) {
            return (Boolean) isAdminAttr;
        } else if (isAdminAttr instanceof String) {
            return "S".equals(isAdminAttr);
        }
        return false;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        HttpSession session = request.getSession(false);

        // Usamos el método robusto
        if (!isAdmin(session)) {
            logger.warn("Intento de acceso no autorizado a AdminTiendas (GET). IP: {}", request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String supervisor = request.getParameter("supervisor");
        String zona = request.getParameter("zona");

        logger.debug("Consultando tiendas. Supervisor: {}, Zona: {}", supervisor, zona);

        List<Tienda> tiendas = new ArrayList<>();
        StringBuilder sqlBuilder = new StringBuilder("SELECT codigo, denominacion, Direccion, Lat, Lon, cp, Poblacion, Cadena, disponible, prioridad, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4 FROM tiendas WHERE 1=1");
        
        if (supervisor != null && !supervisor.isEmpty()) sqlBuilder.append(" AND Supervisor = ?");
        if (zona != null && !zona.isEmpty()) sqlBuilder.append(" AND Zona = ?");
        
        sqlBuilder.append(" ORDER BY denominacion");

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sqlBuilder.toString())) {
            
            int paramIndex = 1;
            if (supervisor != null && !supervisor.isEmpty()) stmt.setString(paramIndex++, supervisor);
            if (zona != null && !zona.isEmpty()) stmt.setString(paramIndex++, zona);

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
                    t.prioridad = rs.getInt("prioridad");
                    t.huecosTurno1 = rs.getInt("HuecosTurno1");
                    t.huecosTurno2 = rs.getInt("HuecosTurno2");
                    t.huecosTurno3 = rs.getInt("HuecosTurno3");
                    t.huecosTurno4 = rs.getInt("HuecosTurno4");
                    tiendas.add(t);
                }
            }
            
            mapper.writeValue(response.getWriter(), tiendas);

        } catch (SQLException e) {
            logger.error("Error SQL al obtener la lista de tiendas.", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            Map<String, String> errorMap = new HashMap<>();
            errorMap.put("error", "Error interno de base de datos");
            mapper.writeValue(response.getWriter(), errorMap);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);

        // Usamos el método robusto
        if (!isAdmin(session)) {
            logger.warn("Intento de acceso no autorizado a AdminTiendas (POST). IP: {}", request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Acceso denegado.");
            mapper.writeValue(response.getWriter(), jsonResponse);
            return;
        }
        
        String adminUser = (String) session.getAttribute("usuario");
        String action = request.getParameter("action");

        if ("save".equals(action)) {
            try {
                boolean isUpdate = Boolean.parseBoolean(request.getParameter("isUpdate"));
                int codigo = Integer.parseInt(request.getParameter("codigo"));
                
                String denominacion = request.getParameter("denominacion");
                String direccion = request.getParameter("direccion");
                String lat = request.getParameter("lat");
                String lon = request.getParameter("lon");
                String cp = request.getParameter("cp");
                String poblacion = request.getParameter("poblacion");
                String cadena = request.getParameter("cadena");
                String disponible = request.getParameter("disponible");
                int prioridad = Integer.parseInt(request.getParameter("prioridad"));
                int h1 = Integer.parseInt(request.getParameter("huecosTurno1"));
                int h2 = Integer.parseInt(request.getParameter("huecosTurno2"));
                int h3 = Integer.parseInt(request.getParameter("huecosTurno3"));
                int h4 = Integer.parseInt(request.getParameter("huecosTurno4"));

                try (Connection conn = DatabaseUtil.getConnection()) {
                    if (isUpdate) {
                        String sqlUpdate = "UPDATE tiendas SET denominacion = ?, Direccion = ?, Lat = ?, Lon = ?, cp = ?, Poblacion = ?, Cadena = ?, disponible = ?, HuecosTurno1 = ?, HuecosTurno2 = ?, HuecosTurno3 = ?, HuecosTurno4 = ?, prioridad = ?, notificar = 'S' WHERE codigo = ?";
                        try (PreparedStatement stmt = conn.prepareStatement(sqlUpdate)) {
                            stmt.setString(1, denominacion); stmt.setString(2, direccion); stmt.setString(3, lat); stmt.setString(4, lon);
                            stmt.setString(5, cp); stmt.setString(6, poblacion); stmt.setString(7, cadena); stmt.setString(8, disponible);
                            stmt.setInt(9, h1); stmt.setInt(10, h2); stmt.setInt(11, h3); stmt.setInt(12, h4);
                            stmt.setInt(13, prioridad); stmt.setInt(14, codigo);
                            
                            int rows = stmt.executeUpdate();
                            if(rows > 0) {
                                logger.info("Tienda {} actualizada correctamente por {}", codigo, adminUser);
                                LogUtil.logOperation(conn, "ADMIN_UPDATE_T", adminUser, "Actualizada tienda: " + codigo);
                            } else {
                                logger.warn("Se intentó actualizar la tienda {} pero no se encontró registro.", codigo);
                            }
                        }
                    } else {
                        String sqlInsert = "INSERT INTO tiendas (codigo, denominacion, Direccion, Lat, Lon, cp, Poblacion, Cadena, disponible, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, prioridad, notificar) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'S')";
                        try (PreparedStatement stmt = conn.prepareStatement(sqlInsert)) {
                            stmt.setInt(1, codigo); stmt.setString(2, denominacion); stmt.setString(3, direccion); stmt.setString(4, lat);
                            stmt.setString(5, lon); stmt.setString(6, cp); stmt.setString(7, poblacion); stmt.setString(8, cadena);
                            stmt.setString(9, disponible); stmt.setInt(10, h1); stmt.setInt(11, h2); stmt.setInt(12, h3);
                            stmt.setInt(13, h4); stmt.setInt(14, prioridad);
                            
                            stmt.executeUpdate();
                            logger.info("Nueva tienda {} creada correctamente por {}", codigo, adminUser);
                            LogUtil.logOperation(conn, "ADMIN_CREATE_T", adminUser, "Creada tienda: " + codigo);
                        }
                    }
                    jsonResponse.put("success", true);
                }
            } catch (Exception e) {
                logger.error("Error crítico al guardar la tienda.", e);
                jsonResponse.put("success", false);
                jsonResponse.put("message", "Error al guardar la tienda: " + e.getMessage());
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            
            mapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}