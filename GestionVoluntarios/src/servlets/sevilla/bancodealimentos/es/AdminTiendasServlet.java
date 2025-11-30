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

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointReplicationUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/admin-tiendas")
public class AdminTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión actualizada
    private final Gson gson = new Gson();

    // Clase interna para representar una tienda
    private static class Tienda {
        int codigo, prioridad, huecosTurno1, huecosTurno2, huecosTurno3, huecosTurno4;
        String denominacion, direccion, lat, lon, cp, poblacion, supervisor, disponible;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !((boolean)session.getAttribute("isAdmin"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String supervisor = request.getParameter("supervisor");
        String zona = request.getParameter("zona");

        List<Tienda> tiendas = new ArrayList<>();
        StringBuilder sqlBuilder = new StringBuilder("SELECT codigo, denominacion, Direccion, Lat, Lon, cp, Poblacion, Supervisor, disponible, prioridad, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4 FROM tiendas WHERE 1=1");
        
        if (supervisor != null && !supervisor.isEmpty()) {
            sqlBuilder.append(" AND Supervisor = ?");
        }
        if (zona != null && !zona.isEmpty()) {
            sqlBuilder.append(" AND Zona = ?");
        }
        sqlBuilder.append(" ORDER BY denominacion");

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sqlBuilder.toString())) {
            
            int paramIndex = 1;
            if (supervisor != null && !supervisor.isEmpty()) {
                stmt.setString(paramIndex++, supervisor);
            }
            if (zona != null && !zona.isEmpty()) {
                stmt.setString(paramIndex++, zona);
            }

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Tienda t = new Tienda();
                t.codigo = rs.getInt("codigo");
                t.denominacion = rs.getString("denominacion");
                t.direccion = rs.getString("Direccion");
                t.lat = rs.getString("Lat");
                t.lon = rs.getString("Lon");
                t.cp = rs.getString("cp");
                t.poblacion = rs.getString("Poblacion");
                t.supervisor = rs.getString("Supervisor");
                t.disponible = rs.getString("disponible");
                t.prioridad = rs.getInt("prioridad");
                t.huecosTurno1 = rs.getInt("HuecosTurno1");
                t.huecosTurno2 = rs.getInt("HuecosTurno2");
                t.huecosTurno3 = rs.getInt("HuecosTurno3");
                t.huecosTurno4 = rs.getInt("HuecosTurno4");
                tiendas.add(t);
            }
        } catch (SQLException e) {
            throw new ServletException("Error de base de datos al obtener tiendas", e);
        }

        response.getWriter().write(gson.toJson(tiendas));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonObject jsonResponse = new JsonObject();
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !((boolean)session.getAttribute("isAdmin"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Acceso denegado.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }
        
        String adminUser = (String) session.getAttribute("usuario");
        String action = request.getParameter("action");

        if ("save".equals(action)) {
            try (Connection conn = DatabaseUtil.getConnection()) {
                conn.setAutoCommit(false);

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

                if (isUpdate) {
                    String sqlUpdate = "UPDATE tiendas SET denominacion = ?, Direccion = ?, Lat = ?, Lon = ?, cp = ?, Poblacion = ?, Cadena = ?, disponible = ?, HuecosTurno1 = ?, HuecosTurno2 = ?, HuecosTurno3 = ?, HuecosTurno4 = ?, prioridad = ?, notificar = 'S' WHERE codigo = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(sqlUpdate)) {
                        stmt.setString(1, denominacion);
                        stmt.setString(2, direccion);
                        stmt.setString(3, lat);
                        stmt.setString(4, lon);
                        stmt.setString(5, cp);
                        stmt.setString(6, poblacion);
                        stmt.setString(7, cadena);
                        stmt.setString(8, disponible);
                        stmt.setInt(9, h1);
                        stmt.setInt(10, h2);
                        stmt.setInt(11, h3);
                        stmt.setInt(12, h4);
                        stmt.setInt(13, prioridad);
                        stmt.setInt(14, codigo);
                        stmt.executeUpdate();
                        LogUtil.logOperation(conn, "ADMIN_UPDATE_T", adminUser, "Actualizada tienda: " + codigo);
                    }
                } else {
                    String sqlInsert = "INSERT INTO tiendas (codigo, denominacion, Direccion, Lat, Lon, cp, Poblacion, Cadena, disponible, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, prioridad, notificar) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'S')";
                    try (PreparedStatement stmt = conn.prepareStatement(sqlInsert)) {
                        stmt.setInt(1, codigo);
                        stmt.setString(2, denominacion);
                        stmt.setString(3, direccion);
                        stmt.setString(4, lat);
                        stmt.setString(5, lon);
                        stmt.setString(6, cp);
                        stmt.setString(7, poblacion);
                        stmt.setString(8, cadena);
                        stmt.setString(9, disponible);
                        stmt.setInt(10, h1);
                        stmt.setInt(11, h2);
                        stmt.setInt(12, h3);
                        stmt.setInt(13, h4);
                        stmt.setInt(14, prioridad);
                        stmt.executeUpdate();
                        LogUtil.logOperation(conn, "ADMIN_CREATE_T", adminUser, "Creada tienda: " + codigo);
                    }
                }

                Map<String, Object> spData = new HashMap<>();
                spData.put("Title", denominacion);
                spData.put("Direccion", direccion);
                spData.put("Latitud", lat);
                spData.put("Longitud", lon);
                spData.put("CP", cp);
                spData.put("Poblacion", poblacion);
                spData.put("Cadena", cadena);
                spData.put("Disponible", disponible);
                spData.put("Prioridad", prioridad);
                spData.put("HuecosTurno1", h1);
                spData.put("HuecosTurno2", h2);
                spData.put("HuecosTurno3", h3);
                spData.put("HuecosTurno4", h4);

                String rowUuid = String.valueOf(codigo);
                SharepointReplicationUtil.Operation operation = isUpdate ? SharepointReplicationUtil.Operation.UPDATE : SharepointReplicationUtil.Operation.INSERT;
                String listName = "Tiendas";

                // ** INICIO DE LA CORRECCIÓN **
                SharepointReplicationUtil.replicate(conn, SharepointUtil.SITE_ID, listName, spData, operation, rowUuid);
                // ** FIN DE LA CORRECCIÓN **

                LogUtil.logOperation(conn, "REPLICATE_TIENDA", adminUser, "Tienda " + codigo + " replicada a SharePoint con operación " + operation.name());
                
                conn.commit();
                jsonResponse.addProperty("success", true);

            } catch (Exception e) {
                e.printStackTrace();
                jsonResponse.addProperty("success", false);
                jsonResponse.addProperty("message", "Error al guardar la tienda: " + e.getMessage());
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            response.getWriter().write(jsonResponse.toString());
        }
    }
}
