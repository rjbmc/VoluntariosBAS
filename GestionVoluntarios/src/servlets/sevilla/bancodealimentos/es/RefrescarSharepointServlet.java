package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

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

@WebServlet("/refrescar-sharepoint")
public class RefrescarSharepointServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // ... (código sin cambios)
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonObject jsonResponse = new JsonObject();
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !session.getAttribute("isAdmin").equals(true)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Acceso denegado. Se requieren permisos de administrador.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        String tabla = request.getParameter("table");
        if (tabla == null || tabla.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "No se ha especificado ninguna tabla para sincronizar.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);
            
            int registrosAfectados = 0;
            String nombreLista = "";
            String targetSiteId = SharepointUtil.SP_SITE_ID_VOLUNTARIOS;

            switch (tabla) {
                case "Voluntarios":
                    nombreLista = "voluntarios";
                    SharepointReplicationUtil.deleteAllItems(conn, targetSiteId, nombreLista);
                    registrosAfectados = sincronizarVoluntarios(conn, targetSiteId, nombreLista);
                    break;
                case "Campanas":
                    nombreLista = "campanas";
                    SharepointReplicationUtil.deleteAllItems(conn, targetSiteId, nombreLista);
                    registrosAfectados = sincronizarCampanas(conn, targetSiteId, nombreLista);
                    break;
                default:
                    throw new ServletException("La tabla especificada no es válida para la sincronización.");
            }

            conn.commit();
            LogUtil.logOperation(conn, "SYNC_SHAREPOINT", (String) session.getAttribute("usuario"), "Sincronización completa para la lista: " + nombreLista + ". Registros creados: " + registrosAfectados);
            jsonResponse.addProperty("success", true);
            jsonResponse.addProperty("message", "La lista '" + nombreLista + "' ha sido sincronizada. Se han creado " + registrosAfectados + " registros en SharePoint.");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error al procesar la solicitud: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { e.printStackTrace(); }
            response.getWriter().write(jsonResponse.toString());
        }
    }

    private int sincronizarVoluntarios(Connection conn, String targetSiteId, String listName) throws SQLException {
        String sql = "SELECT * FROM voluntarios";
        int count = 0;
        try (PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> spData = new HashMap<>();
                spData.put("Title", rs.getString("Usuario")); 
                spData.put("SqlRowUUID", rs.getString("SqlRowUUID"));
                spData.put("field_1", rs.getString("Nombre"));
                spData.put("field_2", rs.getString("Apellidos"));
                spData.put("field_3", rs.getString("DNI NIF"));
                spData.put("field_5", rs.getInt("tiendaReferencia"));
                spData.put("field_6", rs.getString("Email"));
                spData.put("field_7", rs.getString("telefono"));
                
                Date fechaNacimiento = rs.getDate("fechaNacimiento");
                if (fechaNacimiento != null) {
                    spData.put("field_8", fechaNacimiento.toString());
                }
                
                // ** CORRECCIÓN 1: Convertir el CP (varchar) a un número entero antes de enviarlo **
                try {
                    int cp = Integer.parseInt(rs.getString("cp"));
                    spData.put("field_9", cp);
                } catch (NumberFormatException e) {
                    // Opcional: manejar el caso donde el CP no sea un número válido
                    System.err.println("ADVERTENCIA: No se pudo convertir el CP '" + rs.getString("cp") + "' a número para el usuario " + rs.getString("Usuario"));
                }

                // ** CORRECCIÓN 2: Enviar 'Si'/'No' como texto en lugar de un booleano **
                String administrador = rs.getString("administrador");
                spData.put("field_10", ("S".equalsIgnoreCase(administrador) || "Si".equalsIgnoreCase(administrador)) ? "Si" : "No");

                Date fechaBaja = rs.getDate("fecha_baja");
                if (fechaBaja != null) {
                    spData.put("field_21", fechaBaja.toString());
                }
                
                SharepointReplicationUtil.replicate(conn, targetSiteId, listName, spData, SharepointReplicationUtil.Operation.INSERT, rs.getString("SqlRowUUID"));
                count++;
            }
        }
        return count;
    }

    private int sincronizarCampanas(Connection conn, String targetSiteId, String listName) throws SQLException {
        String sql = "SELECT * FROM campanas";
        int count = 0;
        try (PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> spData = new HashMap<>();
                spData.put("Title", rs.getString("campana"));
                spData.put("SqlRowUUID", rs.getString("SqlRowUUID"));
                spData.put("nombre", rs.getString("campana"));
                
                Date fechaInicio = rs.getDate("fecha1");
                if (fechaInicio != null) {
                    spData.put("fecha_inicio", fechaInicio.toString());
                }
                
                Date fechaFin = rs.getDate("fecha2");
                if (fechaFin != null) {
                    spData.put("fecha_fin", fechaFin.toString());
                }
                
                spData.put("texto_consentimiento", rs.getString("comentarios"));

                String estado = rs.getString("estado");
                spData.put("activa", "S".equalsIgnoreCase(estado) || "Si".equalsIgnoreCase(estado));
                
                SharepointReplicationUtil.replicate(conn, targetSiteId, listName, spData, SharepointReplicationUtil.Operation.INSERT, rs.getString("SqlRowUUID"));
                count++;
            }
        }
        return count;
    }
}