import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import Kiosk from './Kiosk';
import AdminLayout from './AdminLayout';
import Overview from './Overview';
import AddUser from './AddUser';
import EmployeeList from './EmployeeList';
import './index.css';

function App() {
  return (
    <Router>
      <Routes>
        <Route path="/" element={<Navigate to="/kiosk" replace />} />
        <Route path="/kiosk" element={<Kiosk />} />
        
        <Route path="/admin" element={<AdminLayout />}>
          <Route index element={<Overview />} />
          <Route path="add-user" element={<AddUser />} />
          <Route path="users" element={<EmployeeList />} />
        </Route>
      </Routes>
    </Router>
  );
}

export default App;
